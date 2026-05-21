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
    @Column(nullable = false)
    var level: Int = 1,
    @Column(name = "total_exp", nullable = false)
    var totalExp: Long = 0,
    @Column(name = "basic_coin", nullable = false)
    var basicCoin: Long = 0,
    @Column(name = "special_coin", nullable = false)
    var specialCoin: Long = 0,
    // N3 (구현 계획서 v4, 2026-05-21): entitlement SoT 가 user_entitlement 테이블로 일원화됨.
    // 아래 boolean 2개는 deprecated — 읽기는 EntitlementService.hasEntitlement() 로 대체됨.
    // 컬럼은 backfill (V16) 검증 후 별 migration 으로 drop 예정. 현재는 NOT NULL default false 유지.
    @Deprecated("entitlement SoT = user_entitlement 테이블. EntitlementService.hasEntitlement() 사용")
    @Column(name = "entitlement_free_placement", nullable = false)
    var entitlementFreePlacement: Boolean = false,
    @Deprecated("entitlement SoT = user_entitlement 테이블. EntitlementService.hasEntitlement() 사용")
    @Column(name = "entitlement_premium_themes", nullable = false)
    var entitlementPremiumThemes: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    // N10 (구현 계획서 v4, 2026-05-21): 재화/EXP 동시성 — JPA optimistic lock.
    // 동시 record/attendance/purchase 시 basicCoin/totalExp 의 lost update 방어.
    // 충돌 시 ObjectOptimisticLockingFailureException → GlobalExceptionHandler 가 409.
    @Version
    @Column(nullable = false)
    var version: Long = 0,
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val tokens: MutableList<UserToken> = mutableListOf(),
)

enum class UserRole { USER, ADMIN }
