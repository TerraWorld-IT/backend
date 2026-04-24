package com.terraworld.domain.record

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.LocalDate

interface RecordRepository : JpaRepository<ActivityRecord, Long> {

    fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String, pageable: Pageable
    ): Page<ActivityRecord>

    fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
        userId: String, from: LocalDate, to: LocalDate
    ): List<ActivityRecord>

    fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
        userId: String, recordedDate: LocalDate, categoryId: Long
    ): Long

    fun countByUserIdAndIsDeletedFalse(userId: String): Long

    fun countByUserIdAndRecordedDateAndIsDeletedFalse(userId: String, date: LocalDate): Long

    fun countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(userId: String, date: LocalDate): Long

    @Query("""
        SELECT r.category.id, COUNT(r)
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
        GROUP BY r.category.id
    """)
    fun countByCategoryGrouped(userId: String): List<Array<Any>>

    @Query("""
        SELECT MAX(r.recordedDate)
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
    """)
    fun findMaxRecordedDate(userId: String): LocalDate?
}
