package com.terraworld.api.ranking

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.record.RecordRepository
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

    /**
     * ARCH-008-phase-9: 반환 타입을 generated [RankingResponse] 직접. local DTO 삭제 + controller 매퍼 제거.
     * `type` 은 generated enum 으로 — `forValue("engagement"/"decoration")` 안전 (hardcoded).
     */
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
            type = RankingResponse.Type.forValue("engagement"),
            yearMonth = ym.format(YEAR_MONTH_FMT),
            propertyEntries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
        )
    }

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
            type = RankingResponse.Type.forValue("decoration"),
            yearMonth = ym.format(YEAR_MONTH_FMT),
            propertyEntries = entries,
            myRank = myRank,
            myScore = if (myScore > 0) myScore else null,
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

    private fun computeMyRank(
        entries: List<RankingEntry>,
        currentUserId: String,
        myScore: Long,
    ): Int? {
        if (myScore <= 0) return null
        // generated RankingEntry.isSelf 는 nullable Boolean 이므로 `== true` 로 unwrap
        val selfEntry = entries.firstOrNull { it.isSelf == true }
        if (selfEntry != null) return selfEntry.rank
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
