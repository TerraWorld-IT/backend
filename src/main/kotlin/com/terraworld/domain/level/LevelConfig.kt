package com.terraworld.domain.level

import jakarta.persistence.*

@Entity
@Table(name = "level_configs")
class LevelConfig(
    @Id
    val level: Int,
    @Column(name = "required_exp", nullable = false)
    val requiredExp: Long,
    @Column(name = "reward_type", length = 30)
    val rewardType: String? = null,
    @Column(name = "reward_value")
    val rewardValue: Int? = null,
    @Column(name = "max_items", nullable = false)
    val maxItems: Int = 20,
)
