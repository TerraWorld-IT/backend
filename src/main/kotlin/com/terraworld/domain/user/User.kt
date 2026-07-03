package com.terraworld.domain.user

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id
    val id: String,
    @Column(nullable = false, length = 50)
    var nickname: String,
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var role: UserRole = UserRole.USER,
    // 낙서장 P1 read-cutover 완료: 화폐 SoT = user_currency_balances(CurrencyService). 구 basic_coin/
    // special_coin/user_tokens 는 V32 에서 드롭 (엔티티 필드도 제거).
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    // N10: 동시성 — JPA optimistic lock. 충돌 시 ObjectOptimisticLockingFailureException → 409.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

enum class UserRole { USER, ADMIN }
