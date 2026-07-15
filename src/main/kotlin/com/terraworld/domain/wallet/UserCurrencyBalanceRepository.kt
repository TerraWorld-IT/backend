package com.terraworld.domain.wallet

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserCurrencyBalanceRepository : JpaRepository<UserCurrencyBalance, UserCurrencyBalanceId> {
    fun findAllByUserId(userId: String): List<UserCurrencyBalance>

    fun findByUserIdAndCurrencyCode(
        userId: String,
        currencyCode: String,
    ): UserCurrencyBalance?

    /**
     * 감사 MED#6: 원자적 credit. read-modify-write(@Version, retry 無) 는 동시 credit/최초삽입 race 취약.
     * 단일 UPSERT 로 원자 증가 — 호출부 tx 안에서 실행(원자성 유지), row-lock 으로 경합 직렬화.
     * @Version 컬럼은 일관성 위해 함께 증가.
     *
     * BE-09 (2026-07-15 성능 감사): `RETURNING amount` 로 새 잔액을 같은 statement 에서 반환 —
     * 기존 후속 findAmount 재조회 왕복 제거. RETURNING 이 결과 집합을 내므로 @Modifying 없이
     * result 쿼리로 실행한다 (Hibernate 는 query-space 미지정 native 쿼리 실행 전 영속성 컨텍스트를
     * flush — 기존 flushAutomatically semantics 유지). 반환값은 DB 실측치(L1 캐시 우회) — UPSERT 라
     * 항상 정확히 1행.
     */
    @Query(
        value = """
            INSERT INTO user_currency_balances (user_id, currency_code, amount, version)
            VALUES (:userId, :currencyCode, :amount, 0)
            ON CONFLICT (user_id, currency_code)
            DO UPDATE SET amount = user_currency_balances.amount + :amount,
                          version = user_currency_balances.version + 1
            RETURNING amount
            """,
        nativeQuery = true,
    )
    fun creditAtomic(
        @Param("userId") userId: String,
        @Param("currencyCode") currencyCode: String,
        @Param("amount") amount: Long,
    ): Long

    /**
     * 감사 MED#6: 원자적 debit. 조건부 UPDATE(amount >= :amount)로 잔액검사+차감을 단일 statement 원자 수행.
     *
     * BE-09: `RETURNING amount` 로 새 잔액 반환 (findAmount 재조회 제거, creditAtomic 과 동일 패턴).
     * 반환 null = 행 부재 OR 잔액 부족 (기존 rows==0 과 동일 의미) → 호출부가 INSUFFICIENT_FUNDS.
     */
    @Query(
        value = """
            UPDATE user_currency_balances
            SET amount = amount - :amount, version = version + 1
            WHERE user_id = :userId AND currency_code = :currencyCode AND amount >= :amount
            RETURNING amount
            """,
        nativeQuery = true,
    )
    fun debitAtomic(
        @Param("userId") userId: String,
        @Param("currencyCode") currencyCode: String,
        @Param("amount") amount: Long,
    ): Long?
}
