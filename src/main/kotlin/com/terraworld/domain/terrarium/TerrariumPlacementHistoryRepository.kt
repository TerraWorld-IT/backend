package com.terraworld.domain.terrarium

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface TerrariumPlacementHistoryRepository : JpaRepository<TerrariumPlacementHistory, Long> {
    /**
     * 월간 decoration 랭킹 — 지정 기간 내 사용자별 신규 배치 카운트.
     * 결과: [userId, count] 의 Array, count desc 정렬.
     */
    @Query(
        """
        SELECT h.userId, COUNT(h)
        FROM TerrariumPlacementHistory h
        WHERE h.placedAt BETWEEN :start AND :end
        GROUP BY h.userId
        ORDER BY COUNT(h) DESC
    """,
    )
    fun findDecorationRanking(
        start: LocalDateTime,
        end: LocalDateTime,
        pageable: Pageable,
    ): List<Array<Any>>

    /** 아프젝 v2 친구 스코프 decoration 랭킹 — 본인 + 수락된 친구 userId 집합 한정. */
    @Query(
        """
        SELECT h.userId, COUNT(h)
        FROM TerrariumPlacementHistory h
        WHERE h.placedAt BETWEEN :start AND :end
          AND h.userId IN :userIds
        GROUP BY h.userId
        ORDER BY COUNT(h) DESC
    """,
    )
    fun findDecorationRankingAmong(
        @Param("userIds") userIds: Collection<String>,
        @Param("start") start: LocalDateTime,
        @Param("end") end: LocalDateTime,
        pageable: Pageable,
    ): List<Array<Any>>

    /**
     * 특정 사용자의 월간 신규 배치 수 (자기 순위 계산용).
     */
    @Query(
        """
        SELECT COUNT(h)
        FROM TerrariumPlacementHistory h
        WHERE h.userId = :userId
          AND h.placedAt BETWEEN :start AND :end
    """,
    )
    fun countByUserAndPeriod(
        userId: String,
        start: LocalDateTime,
        end: LocalDateTime,
    ): Long
}
