package com.terraworld.api.exchange

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
import kotlin.test.assertEquals

/**
 * ExchangeService 재시도 정책 커버 (낙서장 P1).
 * 낙관적 락(@Version) 경합 시 [ExchangeExecutor] 를 fresh tx 로 최대 3회 재시도.
 */
class ExchangeServiceTest {
    private val executor = mock(ExchangeExecutor::class.java)
    private val service = ExchangeService(executor)

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
}
