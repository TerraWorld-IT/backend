package com.terraworld.domain.record

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface RecordRepository : JpaRepository<ActivityRecord, Long> {
    fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<ActivityRecord>

    fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
        userId: String,
        from: LocalDate,
        to: LocalDate,
    ): List<ActivityRecord>

    fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
        userId: String,
        recordedDate: LocalDate,
        categoryId: Long,
    ): Long

    fun countByUserIdAndIsDeletedFalse(userId: String): Long

    fun countByUserIdAndRecordedDateAndIsDeletedFalse(
        userId: String,
        date: LocalDate,
    ): Long

    fun countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(
        userId: String,
        date: LocalDate,
    ): Long

    @Query(
        """
        SELECT r.category.id, COUNT(r)
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
        GROUP BY r.category.id
    """,
    )
    fun countByCategoryGrouped(userId: String): List<Array<Any>>

    @Query(
        """
        SELECT MAX(r.recordedDate)
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
    """,
    )
    fun findMaxRecordedDate(userId: String): LocalDate?

    /**
     * 월간 engagement 랭킹용 — 지정 기간 내 사용자별 활동 기록 수.
     * 결과: [userId, count] 의 Array, count desc 정렬.
     */
    @Query(
        """
        SELECT r.user.id, COUNT(r)
        FROM ActivityRecord r
        WHERE r.recordedDate BETWEEN :start AND :end
          AND r.isDeleted = false
        GROUP BY r.user.id
        ORDER BY COUNT(r) DESC
    """,
    )
    fun findEngagementRanking(
        start: LocalDate,
        end: LocalDate,
        pageable: Pageable,
    ): List<Array<Any>>

    /**
     * 특정 사용자의 월간 활동 기록 수 (자기 순위 계산용).
     */
    @Query(
        """
        SELECT COUNT(r)
        FROM ActivityRecord r
        WHERE r.user.id = :userId
          AND r.recordedDate BETWEEN :start AND :end
          AND r.isDeleted = false
    """,
    )
    fun countByUserAndPeriod(
        userId: String,
        start: LocalDate,
        end: LocalDate,
    ): Long
}
