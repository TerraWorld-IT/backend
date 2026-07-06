package com.terraworld.api.exchange

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.exchange.ExchangeDailyUsage
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
import com.terraworld.domain.exchange.ExchangeRateRepository
import io.terraworld.api.model.ExchangeResult
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 재화 교환 트랜잭션 경계 (낙서장 P1). 단일 @Transactional 안에서
 * cap 검증 → 정수 환산 → debit(from) → credit(to) → usage 기록 → updatedCurrency.
 * 잔액 mutation 은 CurrencyService(@Version 낙관락) 위임 — 원자적 balance+ledger.
 * 낙관락 재시도는 [ExchangeService] 가 fresh tx 로 감쌈(self-invocation proxy 우회 회피).
 * 설계 SoT: docs/plans/2026-07-01-economy-7currency-design.md
 */
@Service
class ExchangeExecutor(
    private val exchangeRateRepository: ExchangeRateRepository,
    private val exchangeDailyUsageRepository: ExchangeDailyUsageRepository,
    private val currencyService: CurrencyService,
) {
    companion object {
        const val REASON_EXCHANGE = "EXCHANGE"
        const val REF_TYPE = "EXCHANGE"
        const val MAX_AMOUNT = 10_000L
        const val COMBINED_ACTIVITY_CAP = 1500L
        const val FEE_DENOM = 10_000L
        val ACTIVITY_CODES = listOf(CurrencyCode.DEW, CurrencyCode.SUN, CurrencyCode.BOLT, CurrencyCode.WIND)
    }

    // 알려진 한계 (code-review R9, accepted — prototype hardening backlog):
    //   요청-레벨 idempotency key 는 없다(전 money POST — exchange/booster/purchase 공통 설계). 동일 POST 재전송 시
    //   각 요청이 새 거래로 처리된다. 단, 이는 **사용자 자기-손해(이중 차감)**이지 farming/attacker-gain 이 아니며
    //   (double-submit 은 재화를 2배 지불하고 2배 받는 것), FE in-page guard(exchanging ref) + 잔액 원자성
    //   (debit/credit atomic) + per-user-day advisory lock + daily cap 으로 현실적 위험이 bound 된다.
    //   완전 방어(서버측 dedup inbox)는 전 money POST 를 아우르는 cross-cutting 결정이라 human/product 백로그로 이연.
    @Transactional
    fun execute(
        userId: String,
        from: String,
        to: String,
        amount: Long,
    ): ExchangeResult {
        if (amount < 1 || amount > MAX_AMOUNT) throw BusinessException(ErrorCode.INVALID_INPUT)

        val rate =
            exchangeRateRepository.findByFromCodeAndToCode(from, to)
                ?: throw BusinessException(ErrorCode.PAIR_NOT_ALLOWED)

        val today = KstTime.today() // 낙서장 리뷰 S#2: cap day 경계는 KST (JVM UTC 아님)

        // 감사 HIGH#1: per-user-day 교환 직렬화. combined activity→COIN cap 은 여러 pair row 를 SUM 하는
        // cross-row aggregate라 per-row @Version 으로 못 막음(다른 from-code 동시 교환은 version 충돌 없이 cap 우회).
        // advisory lock 으로 cap 검증+usage 기록을 race-free 로. tx 종료 시 자동 해제.
        exchangeDailyUsageRepository.acquireUserDayLock("$userId|$today")

        // 페어별 일일 한도
        val usage = exchangeDailyUsageRepository.findByUserIdAndFromCodeAndToCodeAndUsageDate(userId, from, to, today)
        val prior = usage?.fromAmount ?: 0L
        if (prior + amount > rate.dailyCap) throw BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED)

        // 활동토큰 합산 → COIN 한도
        if (to == CurrencyCode.COIN && from in ACTIVITY_CODES) {
            val combined = exchangeDailyUsageRepository.sumFromAmount(userId, ACTIVITY_CODES, today)
            if (combined + amount > COMBINED_ACTIVITY_CAP) throw BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED)
        }

        // 정수 환산: grossTo = floor(amount * rate); netTo = floor(gross * (FEE_DENOM - feeBps) / FEE_DENOM)
        val gross =
            rate.rate
                .multiply(BigDecimal.valueOf(amount))
                .setScale(0, RoundingMode.FLOOR)
                .toLong()
        val net =
            BigDecimal
                .valueOf(gross)
                .multiply(BigDecimal.valueOf(FEE_DENOM - rate.feeBps))
                .divide(BigDecimal.valueOf(FEE_DENOM), 0, RoundingMode.FLOOR)
                .toLong()
        if (net < 1) throw BusinessException(ErrorCode.AMOUNT_TOO_SMALL)
        val fee = gross - net

        val refKey = "$from->$to"
        // debit 먼저(잔액 부족 시 INSUFFICIENT_FUNDS fail-fast) → credit
        currencyService.debit(userId, from, amount, REASON_EXCHANGE, REF_TYPE, refKey)
        currencyService.credit(userId, to, net, REASON_EXCHANGE, REF_TYPE, refKey)

        // 일일 사용량 누적 (같은 tx read-modify-write)
        val row =
            usage ?: ExchangeDailyUsage(
                userId = userId,
                fromCode = from,
                toCode = to,
                usageDate = today,
                fromAmount = 0,
            )
        row.fromAmount += amount
        exchangeDailyUsageRepository.save(row)

        return ExchangeResult(
            from = from,
            to = to,
            fromAmount = amount,
            grossToAmount = gross,
            feeAmount = fee,
            toAmount = net,
            rate = rateLabel(rate.rate),
            updatedCurrency = currencyService.currencyResponse(userId),
        )
    }

    /** rate(BigDecimal) → "N:M" 표기. 예 0.1 → "10:1", 50 → "1:50". */
    private fun rateLabel(rate: BigDecimal): String =
        if (rate.compareTo(BigDecimal.ONE) < 0) {
            val inv = BigDecimal.ONE.divide(rate, 0, RoundingMode.HALF_UP).toLong()
            "$inv:1"
        } else {
            "1:${rate.setScale(0, RoundingMode.HALF_UP).toLong()}"
        }
}
