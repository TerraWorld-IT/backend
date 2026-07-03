package com.terraworld.domain.wallet

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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
     * @Version 컬럼은 일관성 위해 함께 증가. 반환 amount 는 [findAmount] 로 별도 조회(native, L1 캐시 우회).
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            INSERT INTO user_currency_balances (user_id, currency_code, amount, version)
            VALUES (:userId, :currencyCode, :amount, 0)
            ON CONFLICT (user_id, currency_code)
            DO UPDATE SET amount = user_currency_balances.amount + :amount,
                          version = user_currency_balances.version + 1
            """,
        nativeQuery = true,
    )
    fun creditAtomic(
        @Param("userId") userId: String,
        @Param("currencyCode") currencyCode: String,
        @Param("amount") amount: Long,
    ): Int

    /**
     * 감사 MED#6: 원자적 debit. 조건부 UPDATE(amount >= :amount)로 잔액검사+차감을 단일 statement 원자 수행.
     * 반환 rows==0 = 행 부재 OR 잔액 부족 → 호출부가 INSUFFICIENT_FUNDS. rows==1 = 성공.
     */
    @Modifying(flushAutomatically = true)
    @Query(
        value = """
            UPDATE user_currency_balances
            SET amount = amount - :amount, version = version + 1
            WHERE user_id = :userId AND currency_code = :currencyCode AND amount >= :amount
            """,
        nativeQuery = true,
    )
    fun debitAtomic(
        @Param("userId") userId: String,
        @Param("currencyCode") currencyCode: String,
        @Param("amount") amount: Long,
    ): Int

    /** native 잔액 조회 (원자 credit/debit 후 balanceAfter 획득 — JPA L1 캐시 우회). */
    @Query(
        value = "SELECT amount FROM user_currency_balances WHERE user_id = :userId AND currency_code = :currencyCode",
        nativeQuery = true,
    )
    fun findAmount(
        @Param("userId") userId: String,
        @Param("currencyCode") currencyCode: String,
    ): Long?
}
