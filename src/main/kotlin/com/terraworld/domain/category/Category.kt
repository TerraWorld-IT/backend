package com.terraworld.domain.category

import jakarta.persistence.*

@Entity
@Table(name = "categories")
class Category(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, length = 50)
    var name: String,
    @Column(name = "icon_url", length = 500)
    var iconUrl: String? = null,
    @Column(nullable = false, length = 7)
    var color: String = "#000000",
    @Column(name = "token_name", nullable = false, length = 50)
    var tokenName: String,
    @Column(length = 10)
    var emoji: String? = null,
    @Column(name = "base_coin_reward", nullable = false)
    var baseCoinReward: Int = 20,
    @Column(name = "base_token_reward", nullable = false)
    var baseTokenReward: Int = 10,
    @Column(name = "daily_limit", nullable = false)
    var dailyLimit: Int = 5,
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    @Column(name = "is_custom", nullable = false)
    var isCustom: Boolean = false,
    @Column(name = "owner_user_id", length = 255)
    var ownerUserId: String? = null,
)
