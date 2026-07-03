package com.terraworld.domain.currency

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 화폐 config (7종 고정: 코인/루비/반짝이 + 이슬/햇살/번개/바람). V25 seed.
 * 표시/정책 메타만 보유 — 잔액은 user_currency_balances(P1).
 */
@Entity
@Table(name = "currencies")
class Currency(
    @Id
    @Column(length = 16)
    val code: String = "",
    @Column(nullable = false, length = 16)
    val kind: String = "",
    @Column(name = "label_ko", nullable = false, length = 32)
    val labelKo: String = "",
    @Column(name = "is_purchasable", nullable = false)
    val isPurchasable: Boolean = false,
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,
)
