package com.terraworld.domain.growth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDateTime

/**
 * 유저 육성 개체 (낙서장 P3). (user_id, species_code) UNIQUE.
 * - natural_streak: 자연 연속(실패 시 0 리셋) / sparkle_bought_count: 반짝이 구매분(실패해도 유지, §3-8)
 * - last_progress_at: dormancy(키우기 실패=동면) 판정 기준(P0.5 시들기 로직 이식).
 */
@Entity
@Table(name = "growth_instances")
class GrowthInstance(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "species_code", nullable = false, length = 32)
    val speciesCode: String,
    @Column(name = "natural_streak", nullable = false)
    var naturalStreak: Int = 0,
    @Column(name = "sparkle_bought_count", nullable = false)
    var sparkleBoughtCount: Int = 0,
    @Column(name = "current_stage", nullable = false)
    var currentStage: Int = 1,
    @Column(name = "last_progress_at")
    var lastProgressAt: LocalDateTime? = null,
    @Column(name = "dormant_at")
    var dormantAt: LocalDateTime? = null,
    @Column(name = "dormancy_recovered_at")
    var dormancyRecoveredAt: LocalDateTime? = null,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
