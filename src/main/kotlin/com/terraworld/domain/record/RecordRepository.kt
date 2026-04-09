package com.terraworld.domain.record

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface RecordRepository : JpaRepository<ActivityRecord, Long> {

    fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: Long, pageable: Pageable
    ): Page<ActivityRecord>

    fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
        userId: Long, from: LocalDate, to: LocalDate
    ): List<ActivityRecord>

    fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
        userId: Long, recordedDate: LocalDate, categoryId: Long
    ): Long

    fun countByUserIdAndIsDeletedFalse(userId: Long): Long

    fun countByUserIdAndRecordedDateAndIsDeletedFalse(userId: Long, date: LocalDate): Long

    fun countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(userId: Long, date: LocalDate): Long

    @Query("""
        SELECT r.category.id, COUNT(r)
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
        GROUP BY r.category.id
    """)
    fun countByCategoryGrouped(userId: Long): List<Array<Any>>
}
