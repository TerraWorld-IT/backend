package com.terraworld.domain.category

import jakarta.persistence.*

@Entity
@Table(name = "categories")
class Category(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, length = 50)
    val name: String,
    @Column(name = "icon_url", length = 500)
    val iconUrl: String? = null,
    @Column(nullable = false, length = 7)
    val color: String = "#000000",
    @Column(name = "token_name", nullable = false, length = 50)
    val tokenName: String,
    @Column(length = 10)
    val emoji: String? = null,
    @Column(name = "base_coin_reward", nullable = false)
    val baseCoinReward: Int = 20,
    @Column(name = "base_token_reward", nullable = false)
    val baseTokenReward: Int = 10,
    @Column(name = "daily_limit", nullable = false)
    val dailyLimit: Int = 5,
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
)
