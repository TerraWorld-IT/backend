package com.terraworld.domain.exchange

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.io.Serializable
import java.time.LocalDate

/**
 * 유저별 일일 교환 사용량 (낙서장 P1 cap 추적). (user_id, from_code, to_code, usage_date) 복합키.
 * 서비스 로컬 day 경계로 usage_date 기록. 같은 tx 내 read-modify-write(잔액 @Version 락과 동반).
 */
@Entity
@Table(name = "exchange_daily_usage")
@IdClass(ExchangeDailyUsageId::class)
class ExchangeDailyUsage(
    @Id
    @Column(name = "user_id", length = 64)
    val userId: String = "",
    @Id
    @Column(name = "from_code", length = 16)
    val fromCode: String = "",
    @Id
    @Column(name = "to_code", length = 16)
    val toCode: String = "",
    @Id
    @Column(name = "usage_date")
    val usageDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "from_amount", nullable = false)
    var fromAmount: Long = 0,
    // 리뷰 S#1(CRIT): 동시 교환 cap TOCTOU 방어 — 낙관락. 경합 시 ExchangeService 가 재시도(3x).
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

data class ExchangeDailyUsageId(
    val userId: String = "",
    val fromCode: String = "",
    val toCode: String = "",
    val usageDate: LocalDate = LocalDate.EPOCH,
) : Serializable

interface ExchangeDailyUsageRepository : JpaRepository<ExchangeDailyUsage, ExchangeDailyUsageId> {
    fun findByUserIdAndFromCodeAndToCodeAndUsageDate(
        userId: String,
        fromCode: String,
        toCode: String,
        usageDate: LocalDate,
    ): ExchangeDailyUsage?

    /** 활동토큰(DEW/SUN/BOLT/WIND) → COIN 합계 (combined daily cap 검증용). */
    @Query(
        """
        SELECT COALESCE(SUM(u.fromAmount), 0)
        FROM ExchangeDailyUsage u
        WHERE u.userId = :userId AND u.toCode = 'COIN'
          AND u.fromCode IN :fromCodes AND u.usageDate = :usageDate
        """,
    )
    fun sumFromAmount(
        @Param("userId") userId: String,
        @Param("fromCodes") fromCodes: List<String>,
        @Param("usageDate") usageDate: LocalDate,
    ): Long

    /**
     * 감사 HIGH#1: per-user-day 교환 직렬화용 tx-scoped advisory lock. combined activity→COIN cap 은
     * 여러 pair row 를 SUM 하는 cross-row aggregate라 per-row @Version 으로 못 막는다(다른 from-code 동시 교환은
     * 서로 다른 row → version 충돌 없음 → cap TOCTOU fail-open). 본 lock 이 같은 (user, day) 교환을 직렬화해
     * SUM 검증을 race-free 로 만든다. tx 종료 시 자동 해제. 서브쿼리로 void 함수 실행 + BIGINT 1 반환.
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireUserDayLock(
        @Param("key") key: String,
    ): Int
}
