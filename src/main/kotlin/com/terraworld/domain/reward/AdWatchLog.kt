package com.terraworld.domain.reward

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "ad_watch_logs")
class AdWatchLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(name = "user_id", nullable = false)
    val userId: String,

    @Column(name = "watched_at", nullable = false, updatable = false)
    val watchedAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "reward_special_coins", nullable = false)
    val rewardSpecialCoins: Int = 1,
)
