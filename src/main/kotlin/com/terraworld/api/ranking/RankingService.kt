package com.terraworld.api.ranking

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.RankingEntry
import io.terraworld.api.model.RankingResponse
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/**
 * 월간 랭킹 (engagement / decoration) + 아프젝 v2 `items`(보유 아이템 수, 월 무관) · `scope`(all / friends).
 *
 * - `items`: 점수 = user_items distinct slug 수(비판매 정령·배경 포함). 0점도 myRank 를 산출(동점 올림픽 — 상위 N 밖이면
 *   마지막 rank+1), `yearMonth` 는 요청값 echo(없으면 null).
 * - `friends`: 본인 + 수락된 invite 기반 친구 userId 집합으로 집계를 한정한다 (세 type 공통).
 */
@Service
class RankingService(
    private val recordRepository: RecordRepository,
    private val userRepository: UserRepository,
    private val placementHistoryRepository: TerrariumPlacementHistoryRepository,
    private val userItemRepository: UserItemRepository,
    private val inviteRepository: InviteRepository,
) {
    companion object {
        private val YEAR_MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        private const val MIN_LIMIT = 10
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 50
        const val SCOPE_ALL = "all"
        const val SCOPE_FRIENDS = "friends"
    }

    /**
     * ARCH-008-phase-9: 반환 타입을 generated [RankingResponse] 직접. local DTO 삭제 + controller 매퍼 제거.
     * `type` 은 generated enum 으로 — `forValue("engagement"/"decoration"/"items")` 안전 (hardcoded).
     */
    @Transactional(readOnly = true)
    fun getMonthly(
        currentUserId: String,
        type: String,
        yearMonth: String?,
        limit: Int,
        scope: String = SCOPE_ALL,
    ): RankingResponse {
        val effectiveLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT).let { if (it == 0) DEFAULT_LIMIT else it }
        val scopeEnum =
            when (scope.lowercase()) {
                SCOPE_ALL -> RankingResponse.Scope.ALL
                SCOPE_FRIENDS -> RankingResponse.Scope.FRIENDS
                else -> throw BusinessException(ErrorCode.BAD_REQUEST)
            }
        // 친구 스코프: 본인 + 수락된 invite 상대(방향 무관). null = 전체.
        val scopedUserIds: Set<String>? =
            if (scopeEnum == RankingResponse.Scope.FRIENDS) resolveFriendScope(currentUserId) else null

        // items 는 월 무관 — yearMonth 는 형식 검증만 하고 echo 한다 (미래 월 거부는 월 집계에만 의미가 있어 적용하지 않음).
        if (type == "items") {
            val echoed = yearMonth?.takeIf { it.isNotBlank() }?.let { parseYearMonth(it).format(YEAR_MONTH_FMT) }
            return buildItemsRanking(currentUserId, scopeEnum, scopedUserIds, echoed, effectiveLimit)
        }

        val ym = parseYearMonth(yearMonth)
        if (ym.isAfter(YearMonth.now())) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }
        val start = ym.atDay(1)
        val end = ym.atEndOfMonth()

        return when (type) {
            "engagement" -> buildEngagementRanking(currentUserId, scopeEnum, scopedUserIds, ym, start, end, effectiveLimit)
            "decoration" -> buildDecorationRanking(currentUserId, scopeEnum, scopedUserIds, ym, effectiveLimit)
            else -> throw BusinessException(ErrorCode.BAD_REQUEST)
        }
    }

    private fun resolveFriendScope(currentUserId: String): Set<String> =
        inviteRepository
            .findAcceptedInvolvingUser(currentUserId)
            .mapNotNull { invite ->
                when (currentUserId) {
                    invite.inviterUserId -> invite.inviteeUserId
                    invite.inviteeUserId -> invite.inviterUserId
                    else -> null
                }
            }.toMutableSet()
            .apply { add(currentUserId) }

    private fun buildEngagementRanking(
        currentUserId: String,
        scope: RankingResponse.Scope,
        scopedUserIds: Set<String>?,
        ym: YearMonth,
        start: LocalDate,
        end: LocalDate,
        limit: Int,
    ): RankingResponse {
        val page = PageRequest.of(0, limit)
        val rows =
            if (scopedUserIds == null) {
                recordRepository.findEngagementRanking(start, end, page)
            } else {
                recordRepository.findEngagementRankingAmong(scopedUserIds, start, end, page)
            }
        val entries = toEntries(currentUserId, rows)

        val myScore = recordRepository.countByUserAndPeriod(currentUserId, start, end)
        val myRank = computeMyRank(entries, currentUserId, myScore, rankZero = false)

        return RankingResponse(
            type = RankingResponse.Type.forValue("engagement"),
            scope = scope,
            yearMonth = ym.format(YEAR_MONTH_FMT),
            propertyEntries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
        )
    }

    private fun buildDecorationRanking(
        currentUserId: String,
        scope: RankingResponse.Scope,
        scopedUserIds: Set<String>?,
        ym: YearMonth,
        limit: Int,
    ): RankingResponse {
        val start = ym.atDay(1).atStartOfDay()
        val end = ym.atEndOfMonth().atTime(LocalTime.MAX)
        val page = PageRequest.of(0, limit)
        val rows =
            if (scopedUserIds == null) {
                placementHistoryRepository.findDecorationRanking(start, end, page)
            } else {
                placementHistoryRepository.findDecorationRankingAmong(scopedUserIds, start, end, page)
            }
        val entries = toEntries(currentUserId, rows)

        val myScore = placementHistoryRepository.countByUserAndPeriod(currentUserId, start, end)
        val myRank = computeMyRank(entries, currentUserId, myScore, rankZero = false)

        return RankingResponse(
            type = RankingResponse.Type.forValue("decoration"),
            scope = scope,
            yearMonth = ym.format(YEAR_MONTH_FMT),
            propertyEntries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
        )
    }

    /** 아프젝 v2 items: 월 무관 현재 보유 수. 0점도 myRank/myScore 산출. */
    private fun buildItemsRanking(
        currentUserId: String,
        scope: RankingResponse.Scope,
        scopedUserIds: Set<String>?,
        yearMonthEcho: String?,
        limit: Int,
    ): RankingResponse {
        val page = PageRequest.of(0, limit)
        val rows =
            if (scopedUserIds == null) {
                userItemRepository.findItemsRanking(page)
            } else {
                userItemRepository.findItemsRankingAmong(scopedUserIds, page)
            }
        val entries = toEntries(currentUserId, rows)
        val myScore = userItemRepository.countDistinctSlugsByUserId(currentUserId)
        val myRank = computeMyRank(entries, currentUserId, myScore, rankZero = true)

        return RankingResponse(
            type = RankingResponse.Type.forValue("items"),
            scope = scope,
            yearMonth = yearMonthEcho,
            propertyEntries = entries,
            myRank = myRank,
            myScore = myScore,
        )
    }

    private fun toEntries(
        currentUserId: String,
        rows: List<Array<Any>>,
    ): List<RankingEntry> {
        if (rows.isEmpty()) return emptyList()
        val userIds = rows.map { it[0] as String }
        val nicknameById = userRepository.findAllById(userIds).associate { it.id to it.nickname }

        var lastScore: Long = -1
        var rank = 0
        val entries = mutableListOf<RankingEntry>()
        rows.forEachIndexed { idx, row ->
            val userId = row[0] as String
            val score = (row[1] as Number).toLong()
            if (score != lastScore) {
                rank = idx + 1
                lastScore = score
            }
            entries +=
                RankingEntry(
                    rank = rank,
                    userId = userId,
                    nickname = nicknameById[userId] ?: "익명",
                    score = score,
                    isSelf = userId == currentUserId,
                )
        }
        return entries
    }

    /**
     * 자기 순위. [rankZero]=false(월 집계) 면 0점은 null, true(items) 면 0점도 산출.
     * entries 안에 있으면 그 rank, 밖이면 동점 올림픽 규칙으로 "마지막 rank + 1" (같은 점수의 마지막 entry 가 있으면 그 rank).
     */
    private fun computeMyRank(
        entries: List<RankingEntry>,
        currentUserId: String,
        myScore: Long,
        rankZero: Boolean,
    ): Int? {
        if (myScore <= 0 && !rankZero) return null
        // generated RankingEntry.isSelf 는 nullable Boolean 이므로 `== true` 로 unwrap
        val selfEntry = entries.firstOrNull { it.isSelf == true }
        if (selfEntry != null) return selfEntry.rank
        val last = entries.lastOrNull() ?: return 1
        // 상위 N 밖인데 마지막 entry 와 동점이면 같은 rank (올림픽), 아니면 다음 rank(= entries 수 + 1)
        return if (last.score == myScore) last.rank else entries.size + 1
    }

    private fun parseYearMonth(input: String?): YearMonth {
        if (input.isNullOrBlank()) return YearMonth.now()
        return try {
            YearMonth.parse(input, YEAR_MONTH_FMT)
        } catch (_: java.time.format.DateTimeParseException) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }
    }
}
