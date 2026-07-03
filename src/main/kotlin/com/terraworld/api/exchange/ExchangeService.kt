package com.terraworld.api.exchange

import io.terraworld.api.model.ExchangeResult
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.stereotype.Service

/**
 * 재화 교환 진입점 (낙서장 P1, 7화폐 directed exchange).
 * 낙관적 락(@Version) 경합 시 최대 [MAX_RETRY]회 재시도 — 각 시도는 [ExchangeExecutor.execute]
 * 의 독립 tx (별 bean 위임으로 self-invocation proxy 우회 회피).
 * 설계 SoT: docs/plans/2026-07-01-economy-7currency-design.md
 */
@Service
class ExchangeService(
    private val executor: ExchangeExecutor,
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
}
