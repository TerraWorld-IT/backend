package com.terraworld.api.currency

import com.terraworld.api.wallet.WalletTransactionService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.currency.CurrencyRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.wallet.UserCurrencyBalance
import com.terraworld.domain.wallet.UserCurrencyBalanceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 정규화 화폐 잔액(user_currency_balances) credit/debit + 원장 append 단일 진입점.
 * 낙서장 리팩토링 P0-4. 각 연산은 @Transactional(잔액 mutation + ledger append atomic).
 *
 * - **동시성**: @Version 낙관적 락. **retry 미탑재(P0)** — 동시 credit/debit 경합 시
 *   ObjectOptimisticLockingFailureException(409)로 fail-loud. P1(서버 주도 grant: RTDN/SSV)
 *   wiring 전 원자 UPDATE 또는 @Retryable 도입 필요(security-review #2).
 * - **음수/오버플로 가드**: debit 잔액부족 fail-fast(INSUFFICIENT_FUNDS) + DB CHECK(amount>=0),
 *   credit 은 Math.addExact 로 Long 오버플로 차단(AMOUNT_OVERFLOW).
 * - **currencyCode 검증**: credit 시 currencies 존재 확인(INVALID_CURRENCY) — FK 위반 raw 500 회피.
 *
 * ⚠️ **half-state (P1 read-cutover 전)**: 본 서비스는 신 substrate 에만 쓰고, 지갑 read(WalletBuilder)는
 * 아직 구 저장을 읽는다. P1 read-cutover 전까지 실 화폐 발행 경로에 wiring 금지(GrantService 참조).
 */
@Service
class CurrencyService(
    private val balanceRepository: UserCurrencyBalanceRepository,
    private val currencyRepository: CurrencyRepository,
    private val userRepository: UserRepository,
    private val walletTransactionService: WalletTransactionService,
) {
    fun balances(userId: String): List<UserCurrencyBalance> = balanceRepository.findAllByUserId(userId)

    /**
     * 신 잔액 substrate 기반 [io.terraworld.api.model.CurrencyResponse] (7종 고정, 0 default).
     * P1 read-cutover 의 read 어댑터 — exchange 등 신 substrate 만 건드리는 응답이 사용.
     * (구 저장 기반 WalletBuilder 와 병존; 전역 cutover 시 WalletBuilder 가 본 메서드를 감쌈)
     */
    fun currencyResponse(userId: String): io.terraworld.api.model.CurrencyResponse {
        val map = balances(userId).associate { it.currencyCode to it.amount }
        return io.terraworld.api.model.CurrencyResponse(
            balances =
                com.terraworld.domain.currency.CurrencyCode.ALL.map { code ->
                    io.terraworld.api.model
                        .CurrencyBalance(code = code, amount = map[code] ?: 0L)
                },
        )
    }

    @Transactional
    fun credit(
        userId: String,
        currencyCode: String,
        amount: Long,
        reason: String,
        refType: String? = null,
        refKey: String? = null,
    ): Long {
        require(amount >= 0) { "credit amount must be >= 0" }
        validateCurrency(currencyCode)
        // 감사 MED#6: 원자적 UPSERT — read-modify-write race(동시 credit/최초삽입) 제거. 호출부 tx 내 실행(원자성 유지).
        // BE-09: RETURNING 이 새 잔액을 같은 statement 로 반환 — 후속 findAmount 재조회 왕복 제거.
        val newAmount = balanceRepository.creditAtomic(userId, currencyCode, amount)
        appendLedger(userId, currencyCode, amount, newAmount, reason, refType, refKey)
        return newAmount
    }

    @Transactional
    fun debit(
        userId: String,
        currencyCode: String,
        amount: Long,
        reason: String,
        refType: String? = null,
        refKey: String? = null,
    ): Long {
        require(amount >= 0) { "debit amount must be >= 0" }
        // 감사 MED#6: 원자적 조건부 UPDATE — 잔액검사+차감을 단일 statement 로.
        // BE-09: RETURNING 으로 새 잔액 반환 — null = 행 부재 OR 잔액 부족 (기존 rows==0 동일 의미).
        val newAmount =
            balanceRepository.debitAtomic(userId, currencyCode, amount)
                ?: throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
        appendLedger(userId, currencyCode, -amount, newAmount, reason, refType, refKey)
        return newAmount
    }

    // BE-08: existsByCode 는 Caffeine TTL 10분 캐시 (화폐 7종 고정 seed — 매 credit 검증 SELECT 제거).
    private fun validateCurrency(currencyCode: String) {
        if (!currencyRepository.existsByCode(currencyCode)) throw BusinessException(ErrorCode.INVALID_CURRENCY)
    }

    private fun appendLedger(
        userId: String,
        currencyCode: String,
        delta: Long,
        balanceAfter: Long,
        reason: String,
        refType: String?,
        refKey: String?,
    ) {
        val user = userRepository.getReferenceById(userId)
        walletTransactionService.appendNormalized(user, currencyCode, delta, balanceAfter, reason, refType, refKey)
    }
}
