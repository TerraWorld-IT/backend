package com.terraworld.api.exchange

import com.terraworld.domain.exchange.ExchangeRate
import com.terraworld.domain.exchange.ExchangeRateId
import com.terraworld.domain.exchange.ExchangeRateRepository
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.CurrencyResponse
import io.terraworld.api.model.ExchangeResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.orm.ObjectOptimisticLockingFailureException
import java.math.BigDecimal
import kotlin.test.assertEquals

/**
 * ExchangeService 재시도 정책 커버 (낙서장 P1).
 * 낙관적 락(@Version) 경합 시 [ExchangeExecutor] 를 fresh tx 로 최대 3회 재시도.
 */
class ExchangeServiceTest {
    private val executor = mock(ExchangeExecutor::class.java)
    private val rateRepository = FakeExchangeRateRepository()
    private val service = ExchangeService(executor, rateRepository)

    private val sample =
        ExchangeResult(
            from = "DEW",
            to = "COIN",
            fromAmount = 100,
            grossToAmount = 10,
            feeAmount = 1,
            toAmount = 9,
            rate = "10:1",
            updatedCurrency = CurrencyResponse(balances = emptyList()),
        )

    @Test
    fun `optimistic 충돌 2회 후 3번째 시도 성공`() {
        whenever(executor.execute(any(), any(), any(), any()))
            .thenThrow(ObjectOptimisticLockingFailureException("conflict", null))
            .thenThrow(ObjectOptimisticLockingFailureException("conflict", null))
            .thenReturn(sample)

        val r = service.exchange("u", "DEW", "COIN", 100)

        assertEquals(9, r.toAmount)
        verify(executor, times(3)).execute(any(), any(), any(), any())
    }

    @Test
    fun `optimistic 충돌 3회 연속이면 예외 전파`() {
        whenever(executor.execute(any(), any(), any(), any()))
            .thenThrow(ObjectOptimisticLockingFailureException("conflict", null))

        assertThrows<ObjectOptimisticLockingFailureException> {
            service.exchange("u", "DEW", "COIN", 100)
        }
        verify(executor, times(3)).execute(any(), any(), any(), any())
    }

    @Test
    fun `허용 환율표는 pair 순서와 환율 수수료 일일 한도를 모두 반환`() {
        rateRepository.save(ExchangeRate("RUBY", "COIN", BigDecimal("50.000000"), 0, 100))
        rateRepository.save(ExchangeRate("DEW", "COIN", BigDecimal("0.100000"), 1000, 500))

        val rates = service.getExchangeRates().rates

        assertEquals(listOf("DEW->COIN", "RUBY->COIN"), rates.map { "${it.from}->${it.to}" })
        assertEquals(BigDecimal("0.100000"), rates[0].rate)
        assertEquals("10:1", rates[0].rateLabel)
        assertEquals(1000, rates[0].feeBps)
        assertEquals(500, rates[0].dailyCap)
        assertEquals("1:50", rates[1].rateLabel)
    }

    /** 실제 JPA 저장소와 같은 findAll 계약을 쓰는 인메모리 환율 저장소. */
    private class FakeExchangeRateRepository :
        FakeJpaRepository<ExchangeRate, ExchangeRateId>(),
        ExchangeRateRepository {
        override fun extractId(entity: ExchangeRate): ExchangeRateId = ExchangeRateId(entity.fromCode, entity.toCode)

        override fun findByFromCodeAndToCode(
            fromCode: String,
            toCode: String,
        ): ExchangeRate? = store.values.firstOrNull { it.fromCode == fromCode && it.toCode == toCode }
    }
}
