package com.terraworld.domain.record

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

interface RecordRepository : JpaRepository<ActivityRecord, Long> {
    fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<ActivityRecord>

    // L3: categoryId 필터 지원 — RecordApi 의 categoryId query 파라미터 계약 정합.
    fun findAllByUserIdAndCategoryIdAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String,
        categoryId: Long,
        pageable: Pageable,
    ): Page<ActivityRecord>

    fun findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalse(
        userId: String,
        categoryId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<ActivityRecord>

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

    /**
     * REC-DELETE-REFARM: 일일 보상 한도(민팅 cap) 전용 카운트 — soft-delete 포함 전건.
     * `...IsDeletedFalse` 카운트로 cap 을 걸면 삭제가 슬롯을 되돌려 create→delete→create 로 무한 재민팅
     * (deleteRecord 는 이미 지급된 화폐를 회수하지 않음)이 가능. cap 은 "그 (user,category,date) 로 지금까지
     * 지급된 보상 횟수" 이므로 삭제된 기록도 세어 재민팅을 차단한다. (표시/통계 카운트는 IsDeletedFalse 유지)
     */
    fun countByUserIdAndRecordedDateAndCategoryId(
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

    /**
     * H4: per-(userId|categoryId|date) 기록 생성 직렬화용 tx-scoped advisory lock.
     * daily-limit 이 count-then-insert 무락이라 동시 요청 시 한도초과+보상중복(TOCTOU fail-open).
     * 본 lock 이 같은 (user, category, day) 기록 생성을 직렬화해 count 검증을 race-free 로 만든다.
     * tx 종료 시 자동 해제. 서브쿼리로 void 함수 실행 + BIGINT 1 반환.
     * (ExchangeDailyUsage.acquireUserDayLock 패턴 재사용.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireRecordDailyLock(
        @Param("key") key: String,
    ): Int
}
