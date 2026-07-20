package com.terraworld.api.exchange

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.exchange.ExchangeDailyUsage
import com.terraworld.domain.exchange.ExchangeDailyUsageId
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
import com.terraworld.domain.exchange.ExchangeRate
import com.terraworld.domain.exchange.ExchangeRateId
import com.terraworld.domain.exchange.ExchangeRateRepository
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.CurrencyResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

/**
 * ExchangeExecutor 코어 로직 커버 (낙서장 P1 경제).
 * - happy: DEW→COIN (10% fee) / RUBY→COIN (fee 0)
 * - PAIR_NOT_ALLOWED / AMOUNT_TOO_SMALL / INVALID_INPUT
 * - per-pair daily cap / combined activity cap → DAILY_LIMIT_EXCEEDED
 * - INSUFFICIENT_FUNDS 전파 (CurrencyService.debit throw)
 * CurrencyService 는 mock — balance/ledger 원자성은 CurrencyServiceTest 소관, 본 test 는 orchestration.
 */
class ExchangeExecutorTest {
    private lateinit var rateRepo: FakeExchangeRateRepository
    private lateinit var usageRepo: FakeExchangeDailyUsageRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var executor: ExchangeExecutor

    // KST 고정 — 실행부(ExchangeExecutor)가 KstTime.today() 를 쓰므로 테스트가
    // LocalDate.now()(시스템 zone)면 CI(UTC) 의 15~24시 창(KST 자정~09시)에서 날짜가
    // 어긋나 daily-cap 시딩/검증 3건이 flake 했다 (2026-07-21 CI 실측 — UTC 18:40 실패).
    private val today: LocalDate = KstTime.today()

    @BeforeEach
    fun setup() {
        rateRepo = FakeExchangeRateRepository()
        usageRepo = FakeExchangeDailyUsageRepository()
        currencyService = mock(CurrencyService::class.java)
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))
        executor = ExchangeExecutor(rateRepo, usageRepo, currencyService)

        rateRepo.save(ExchangeRate("DEW", "COIN", BigDecimal("0.100000"), 1000, 500))
        rateRepo.save(ExchangeRate("SUN", "COIN", BigDecimal("0.100000"), 1000, 500))
        rateRepo.save(ExchangeRate("BOLT", "COIN", BigDecimal("0.100000"), 1000, 500))
        rateRepo.save(ExchangeRate("WIND", "COIN", BigDecimal("0.100000"), 1000, 500))
        rateRepo.save(ExchangeRate("RUBY", "COIN", BigDecimal("50.000000"), 0, 100))
    }

    @Test
    fun `DEW to COIN 100 - gross 10, fee 1, net 9 + debit_credit 호출`() {
        val r = executor.execute("u", "DEW", "COIN", 100)

        assertEquals("DEW", r.from)
        assertEquals("COIN", r.to)
        assertEquals(100, r.fromAmount)
        assertEquals(10, r.grossToAmount)
        assertEquals(1, r.feeAmount)
        assertEquals(9, r.toAmount)
        assertEquals("10:1", r.rate)

        verify(currencyService).debit(eq("u"), eq("DEW"), eq(100L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("u"), eq("COIN"), eq(9L), any(), anyOrNull(), anyOrNull())
        assertEquals(100L, usageRepo.get("u", "DEW", "COIN", today)?.fromAmount)
    }

    @Test
    fun `RUBY to COIN fee 0 - 2 RUBY = gross 100, net 100`() {
        val r = executor.execute("u", "RUBY", "COIN", 2)
        assertEquals(100, r.grossToAmount)
        assertEquals(0, r.feeAmount)
        assertEquals(100, r.toAmount)
        verify(currencyService).credit(eq("u"), eq("COIN"), eq(100L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `미허용 pair 는 PAIR_NOT_ALLOWED`() {
        val ex = assertThrows<BusinessException> { executor.execute("u", "COIN", "RUBY", 100) }
        assertEquals(ErrorCode.PAIR_NOT_ALLOWED, ex.errorCode)
    }

    @Test
    fun `결과 1 미만은 AMOUNT_TOO_SMALL (5 DEW = gross 0)`() {
        val ex = assertThrows<BusinessException> { executor.execute("u", "DEW", "COIN", 5) }
        assertEquals(ErrorCode.AMOUNT_TOO_SMALL, ex.errorCode)
    }

    @Test
    fun `amount 0 또는 상한 초과는 INVALID_INPUT`() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { executor.execute("u", "DEW", "COIN", 0) }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { executor.execute("u", "DEW", "COIN", 10_001) }.errorCode,
        )
    }

    @Test
    fun `per-pair 일일 cap 초과는 DAILY_LIMIT_EXCEEDED`() {
        usageRepo.save(ExchangeDailyUsage("u", "DEW", "COIN", today, 450))
        val ex = assertThrows<BusinessException> { executor.execute("u", "DEW", "COIN", 100) } // 550 > 500
        assertEquals(ErrorCode.DAILY_LIMIT_EXCEEDED, ex.errorCode)
    }

    @Test
    fun `combined 활동토큰 cap 초과는 DAILY_LIMIT_EXCEEDED`() {
        // 3 pair 각 cap(500) 도달 → combined 1500. WIND 1 추가 시 combined 1501 > 1500.
        usageRepo.save(ExchangeDailyUsage("u", "DEW", "COIN", today, 500))
        usageRepo.save(ExchangeDailyUsage("u", "SUN", "COIN", today, 500))
        usageRepo.save(ExchangeDailyUsage("u", "BOLT", "COIN", today, 500))
        val ex = assertThrows<BusinessException> { executor.execute("u", "WIND", "COIN", 10) }
        assertEquals(ErrorCode.DAILY_LIMIT_EXCEEDED, ex.errorCode)
    }

    @Test
    fun `잔액 부족은 INSUFFICIENT_FUNDS 전파`() {
        whenever(currencyService.debit(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))
        val ex = assertThrows<BusinessException> { executor.execute("u", "DEW", "COIN", 100) }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    // ─── 페이크 ─────────────────────────────────────────────────

    private class FakeExchangeRateRepository :
        FakeJpaRepository<ExchangeRate, ExchangeRateId>(),
        ExchangeRateRepository {
        override fun extractId(entity: ExchangeRate): ExchangeRateId = ExchangeRateId(entity.fromCode, entity.toCode)

        override fun findByFromCodeAndToCode(
            fromCode: String,
            toCode: String,
        ): ExchangeRate? = store.values.firstOrNull { it.fromCode == fromCode && it.toCode == toCode }
    }

    private class FakeExchangeDailyUsageRepository :
        FakeJpaRepository<ExchangeDailyUsage, ExchangeDailyUsageId>(),
        ExchangeDailyUsageRepository {
        override fun extractId(entity: ExchangeDailyUsage): ExchangeDailyUsageId = ExchangeDailyUsageId(entity.userId, entity.fromCode, entity.toCode, entity.usageDate)

        fun get(
            userId: String,
            fromCode: String,
            toCode: String,
            usageDate: LocalDate,
        ): ExchangeDailyUsage? = findByUserIdAndFromCodeAndToCodeAndUsageDate(userId, fromCode, toCode, usageDate)

        override fun findByUserIdAndFromCodeAndToCodeAndUsageDate(
            userId: String,
            fromCode: String,
            toCode: String,
            usageDate: LocalDate,
        ): ExchangeDailyUsage? =
            store.values.firstOrNull {
                it.userId == userId && it.fromCode == fromCode && it.toCode == toCode && it.usageDate == usageDate
            }

        override fun sumFromAmount(
            userId: String,
            fromCodes: List<String>,
            usageDate: LocalDate,
        ): Long =
            store.values
                .filter {
                    it.userId == userId &&
                        it.toCode == "COIN" &&
                        it.fromCode in fromCodes &&
                        it.usageDate == usageDate
                }.sumOf { it.fromAmount }

        // 감사 HIGH#1: advisory lock — in-memory Fake 에선 no-op (실 직렬화는 PG pg_advisory_xact_lock).
        override fun acquireUserDayLock(key: String): Int = 1
    }
}
