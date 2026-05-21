package com.terraworld.domain.level

import jakarta.persistence.*

@Entity
@Table(name = "level_configs")
class LevelConfig(
    @Id
    val level: Int,
    // N5 (구현 계획서 v4): admin 레벨 설정 조정 — 필드 var (AdminService.updateLevel)
    @Column(name = "required_exp", nullable = false)
    var requiredExp: Long,
    @Column(name = "reward_type", length = 30)
    var rewardType: String? = null,
    @Column(name = "reward_value")
    var rewardValue: Int? = null,
    @Column(name = "max_items", nullable = false)
    var maxItems: Int = 20,
)
