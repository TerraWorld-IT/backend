package com.terraworld.domain.exchange

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.math.BigDecimal

/**
 * 7화폐 directed exchange rate (낙서장 P1). (from_code, to_code) 복합키.
 * rate = to per from. grossTo = floor(fromAmount * rate); netTo = floor(grossTo * (1 - feeBps/10000)).
 * 존재하는 행 = 허용 pair. 부재 = PAIR_NOT_ALLOWED.
 * 설계 SoT: docs/plans/2026-07-01-economy-7currency-design.md
 */
@Entity
@Table(name = "exchange_rates")
@IdClass(ExchangeRateId::class)
class ExchangeRate(
    @Id
    @Column(name = "from_code", length = 16)
    val fromCode: String = "",
    @Id
    @Column(name = "to_code", length = 16)
    val toCode: String = "",
    @Column(nullable = false)
    val rate: BigDecimal = BigDecimal.ZERO,
    @Column(name = "fee_bps", nullable = false)
    val feeBps: Int = 1000,
    @Column(name = "daily_cap", nullable = false)
    val dailyCap: Long = 0,
)

data class ExchangeRateId(
    val fromCode: String = "",
    val toCode: String = "",
) : Serializable

interface ExchangeRateRepository : JpaRepository<ExchangeRate, ExchangeRateId> {
    fun findByFromCodeAndToCode(
        fromCode: String,
        toCode: String,
    ): ExchangeRate?
}
