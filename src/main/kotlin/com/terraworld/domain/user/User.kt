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
    @Column(name = "entitlement_free_placement", nullable = false)
    var entitlementFreePlacement: Boolean = false,
    @Column(name = "entitlement_premium_themes", nullable = false)
    var entitlementPremiumThemes: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val tokens: MutableList<UserToken> = mutableListOf(),
)

enum class UserRole { USER, ADMIN }
