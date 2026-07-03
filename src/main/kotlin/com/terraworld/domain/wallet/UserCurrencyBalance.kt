package com.terraworld.domain.wallet

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.io.Serializable

/**
 * 정규화 화폐 잔액 (단일축). 낙서장 리팩토링 P0.
 * (user_id, currency_code) 복합키. @Version 낙관적 락(UserToken V17 패턴 승계).
 * amount >= 0 은 DB CHECK + CurrencyService.debit 가드.
 */
@Entity
@Table(name = "user_currency_balances")
@IdClass(UserCurrencyBalanceId::class)
class UserCurrencyBalance(
    @Id
    @Column(name = "user_id")
    val userId: String = "",
    @Id
    @Column(name = "currency_code", length = 16)
    val currencyCode: String = "",
    @Column(nullable = false)
    var amount: Long = 0,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

data class UserCurrencyBalanceId(
    val userId: String = "",
    val currencyCode: String = "",
) : Serializable
