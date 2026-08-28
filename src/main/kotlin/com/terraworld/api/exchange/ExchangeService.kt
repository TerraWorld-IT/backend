package com.terraworld.api.exchange

import com.terraworld.domain.exchange.ExchangeRateRepository
import io.terraworld.api.model.ExchangeRateListResponse
import io.terraworld.api.model.ExchangeRateResponse
import io.terraworld.api.model.ExchangeResult
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 재화 교환 진입점 (낙서장 P1, 7화폐 directed exchange).
 * 낙관적 락(@Version) 경합 시 최대 [MAX_RETRY]회 재시도 — 각 시도는 [ExchangeExecutor.execute]
 * 의 독립 tx (별 bean 위임으로 self-invocation proxy 우회 회피).
 * 설계 SoT: docs/plans/2026-07-01-economy-7currency-design.md
 */
@Service
class ExchangeService(
    private val executor: ExchangeExecutor,
    private val exchangeRateRepository: ExchangeRateRepository,
) {
    companion object {
        const val MAX_RETRY = 3
    }

    fun exchange(
        userId: String,
        from: String,
        to: String,
        amount: Long,
    ): ExchangeResult {
        var attempt = 0
        while (true) {
            try {
                return executor.execute(userId, from, to, amount)
            } catch (e: ObjectOptimisticLockingFailureException) {
                if (++attempt >= MAX_RETRY) throw e
            } catch (e: DataIntegrityViolationException) {
                // 감사 MED#5: 최초 usage row 동시 삽입 경합도 재시도 (advisory lock 으로 실질 방어되나 belt-and-suspenders).
                if (++attempt >= MAX_RETRY) throw e
            }
        }
    }

    /** 허용된 환전 pair 를 고정된 코드 순서로 반환해 클라이언트 표시를 안정화한다. */
    @Transactional(readOnly = true)
    fun getExchangeRates(): ExchangeRateListResponse =
        ExchangeRateListResponse(
            rates =
                exchangeRateRepository
                    .findAll()
                    .sortedWith(compareBy({ it.fromCode }, { it.toCode }))
                    .map { rate ->
                        ExchangeRateResponse(
                            from = rate.fromCode,
                            to = rate.toCode,
                            rate = rate.rate,
                            rateLabel = exchangeRateLabel(rate.rate),
                            feeBps = rate.feeBps,
                            dailyCap = rate.dailyCap,
                        )
                    },
        )
}

/** DB 의 출발 재화 1개당 비율을 ExchangeResult 와 사전 환율표가 공유하는 "N:M" 표기로 변환한다. */
internal fun exchangeRateLabel(rate: BigDecimal): String =
    if (rate.compareTo(BigDecimal.ONE) < 0) {
        val inverse = BigDecimal.ONE.divide(rate, 0, RoundingMode.HALF_UP).toLong()
        "$inverse:1"
    } else {
        "1:${rate.setScale(0, RoundingMode.HALF_UP).toLong()}"
    }
