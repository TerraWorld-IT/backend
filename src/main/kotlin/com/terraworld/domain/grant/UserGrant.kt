package com.terraworld.domain.grant

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 다형 지급 원장 (낙서장 리팩토링 P0). grant_type ∈ {CURRENCY, SPIRIT, ITEM, BACKGROUND}.
 * idempotency_key UNIQUE (actor+operation+payload-hash) → 중복 지급 no-op.
 * 티어해금→정령지급 / 육성→정령 / 이벤트→캐릭터 지급의 원자성·멱등 substrate.
 */
@Entity
@Table(name = "user_grants")
class UserGrant(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "grant_type", nullable = false, length = 16)
    val grantType: String,
    @Column(name = "grant_ref", nullable = false, length = 64)
    val grantRef: String,
    @Column(nullable = false)
    val amount: Long = 1,
    @Column(name = "idempotency_key", nullable = false, length = 160)
    val idempotencyKey: String,
    @Column(nullable = false, length = 32)
    val source: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
