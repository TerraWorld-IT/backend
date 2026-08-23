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

    // BE-16 (2026-07-15 성능 감사): 월 조회 분기가 전건 List 로드 후 PageImpl 로 감싸며 pageable 을
    // 무시하던 계약 위반 수정 — Pageable 을 받아 Page 로 반환 (COUNT + LIMIT/OFFSET).
    // 정렬은 비-월 분기와 동일하게 CreatedAtDesc (페이지네이션에 안정 정렬 필수).
    fun findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String,
        categoryId: Long,
        from: LocalDate,
        to: LocalDate,
        pageable: Pageable,
    ): Page<ActivityRecord>

    fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
        userId: String,
        from: LocalDate,
        to: LocalDate,
        pageable: Pageable,
    ): Page<ActivityRecord>

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
     * BE-05 (2026-07-15 성능 감사): WiltScheduler 배치용 — 전체 사용자의 최신 기록일을
     * GROUP BY 1쿼리로. 기존 유저별 findMaxRecordedDate 루프(테라리움 N개 = N쿼리) 대체.
     * 기록이 전혀 없는 사용자는 결과에 없음 (호출부에서 null 처리).
     */
    @Query(
        """
        SELECT r.user.id AS userId, MAX(r.recordedDate) AS maxRecordedDate
        FROM ActivityRecord r
        WHERE r.isDeleted = false
        GROUP BY r.user.id
    """,
    )
    fun findMaxRecordedDatePerUser(): List<UserMaxRecordedDateProjection>

    /**
     * ADD-BE-02 (2026-07-15 성능 감사): getStatistics 의 today/week/total 3 COUNT 쿼리를
     * 조건부 집계(CASE WHEN) 1쿼리로. 집계 쿼리라 row 0건이어도 항상 1행 반환
     * (SUM 은 그때 null — 호출부에서 0 처리, COUNT 는 0).
     */
    @Query(
        """
        SELECT
            SUM(CASE WHEN r.recordedDate = :today THEN 1 ELSE 0 END) AS todayCount,
            SUM(CASE WHEN r.recordedDate >= :weekAgo THEN 1 ELSE 0 END) AS weekCount,
            COUNT(r) AS totalCount
        FROM ActivityRecord r
        WHERE r.user.id = :userId AND r.isDeleted = false
    """,
    )
    fun countStatisticsSummary(
        @Param("userId") userId: String,
        @Param("today") today: LocalDate,
        @Param("weekAgo") weekAgo: LocalDate,
    ): RecordStatisticsSummary

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

    /** 아프젝 v2 친구 스코프 engagement 랭킹 — 본인 + 수락된 친구 userId 집합 한정. */
    @Query(
        """
        SELECT r.user.id, COUNT(r)
        FROM ActivityRecord r
        WHERE r.recordedDate BETWEEN :start AND :end
          AND r.isDeleted = false
          AND r.user.id IN :userIds
        GROUP BY r.user.id
        ORDER BY COUNT(r) DESC
    """,
    )
    fun findEngagementRankingAmong(
        @Param("userIds") userIds: Collection<String>,
        @Param("start") start: LocalDate,
        @Param("end") end: LocalDate,
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

/** BE-05: WiltScheduler 배치 스캔용 read-only projection (userId + 최신 기록일). */
interface UserMaxRecordedDateProjection {
    val userId: String
    val maxRecordedDate: LocalDate?
}

/** ADD-BE-02: getStatistics 조건부 집계 projection. SUM 은 row 0건 시 null (호출부 0 처리). */
interface RecordStatisticsSummary {
    val todayCount: Long?
    val weekCount: Long?
    val totalCount: Long
}
