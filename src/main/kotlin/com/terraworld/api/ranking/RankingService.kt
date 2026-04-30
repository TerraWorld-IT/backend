package com.terraworld.api.ranking

import com.terraworld.api.ranking.dto.RankingEntry
import com.terraworld.api.ranking.dto.RankingResponse
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.user.UserRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter

@Service
class RankingService(
    private val recordRepository: RecordRepository,
    private val userRepository: UserRepository,
    private val placementHistoryRepository: TerrariumPlacementHistoryRepository,
) {
    companion object {
        private val YEAR_MONTH_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
        private const val MIN_LIMIT = 10
        private const val MAX_LIMIT = 100
        private const val DEFAULT_LIMIT = 50
    }

    @Transactional(readOnly = true)
    fun getMonthly(
        currentUserId: String,
        type: String,
        yearMonth: String?,
        limit: Int,
    ): RankingResponse {
        val ym = parseYearMonth(yearMonth)
        if (ym.isAfter(YearMonth.now())) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }
        val effectiveLimit = limit.coerceIn(MIN_LIMIT, MAX_LIMIT).let { if (it == 0) DEFAULT_LIMIT else it }
        val start = ym.atDay(1)
        val end = ym.atEndOfMonth()

        return when (type) {
            "engagement" -> buildEngagementRanking(currentUserId, ym, start, end, effectiveLimit)
            "decoration" -> buildDecorationRanking(currentUserId, ym, effectiveLimit)
            else -> throw BusinessException(ErrorCode.BAD_REQUEST)
        }
    }

    private fun buildEngagementRanking(
        currentUserId: String,
        ym: YearMonth,
        start: LocalDate,
        end: LocalDate,
        limit: Int,
    ): RankingResponse {
        val rows = recordRepository.findEngagementRanking(start, end, PageRequest.of(0, limit))
        val entries = toEntries(currentUserId, rows)

        val myScore = recordRepository.countByUserAndPeriod(currentUserId, start, end)
        val myRank = computeMyRank(entries, currentUserId, myScore)

        return RankingResponse(
            type = "engagement",
            yearMonth = ym.format(YEAR_MONTH_FMT),
            entries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
        )
    }

    /**
     * decoration 랭킹 — `terrarium_placement_history` 의 월간 신규 배치 카운트 기반.
     *
     * Repository 가 없는 테스트 환경(또는 마이그레이션 V7 미적용 환경)에서는
     * 안전하게 빈 entries 반환. 추후 `TerrariumService` 의 placement upsert 흐름에
     * history insert 가 wiring 되면 실제 카운트가 채워진다.
     */
    private fun buildDecorationRanking(
        currentUserId: String,
        ym: YearMonth,
        limit: Int,
    ): RankingResponse {
        val start = ym.atDay(1).atStartOfDay()
        val end = ym.atEndOfMonth().atTime(LocalTime.MAX)
        val rows = placementHistoryRepository.findDecorationRanking(start, end, PageRequest.of(0, limit))
        val entries = toEntries(currentUserId, rows)

        val myScore = placementHistoryRepository.countByUserAndPeriod(currentUserId, start, end)
        val myRank = computeMyRank(entries, currentUserId, myScore)

        return RankingResponse(
            type = "decoration",
            yearMonth = ym.format(YEAR_MONTH_FMT),
            entries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
        )
    }

    private fun toEntries(
        currentUserId: String,
        rows: List<Array<Any>>,
    ): List<RankingEntry> {
        if (rows.isEmpty()) return emptyList()
        // 닉네임 일괄 조회 (N+1 회피)
        val userIds = rows.map { it[0] as String }
        val nicknameById = userRepository.findAllById(userIds).associate { it.id to it.nickname }

        // 동점자 처리 (올림픽 방식: 같은 점수는 같은 rank, 다음 rank skip)
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
     * entries 안에 currentUserId 가 있으면 그 rank, 아니면 myScore 기반으로 entries 의 마지막 rank + 1 미만이라 가정해 nullable 반환.
     */
    private fun computeMyRank(
        entries: List<RankingEntry>,
        currentUserId: String,
        myScore: Long,
    ): Int? {
        if (myScore <= 0) return null
        val selfEntry = entries.firstOrNull { it.isSelf }
        if (selfEntry != null) return selfEntry.rank
        // entries 밖 — 정확한 글로벌 순위는 별도 쿼리 필요. 여기서는 "entries.last.rank + 1 이상" 표시만
        val lastRank = entries.lastOrNull()?.rank ?: 0
        return if (lastRank == 0) 1 else lastRank + 1
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
