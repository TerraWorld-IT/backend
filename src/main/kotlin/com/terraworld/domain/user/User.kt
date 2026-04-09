package com.terraworld.domain.user

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "users")
class User(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false, unique = true)
    val email: String,

    @Column(name = "password_hash", nullable = false)
    var passwordHash: String,

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

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),

    @OneToMany(mappedBy = "user", cascade = [CascadeType.ALL], orphanRemoval = true)
    val tokens: MutableList<UserToken> = mutableListOf(),
)

enum class UserRole { USER, ADMIN }
