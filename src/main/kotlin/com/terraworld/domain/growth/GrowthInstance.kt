package com.terraworld.domain.growth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

/**
 * 유저 육성 개체 (낙서장 P3 → 아프젝 v2 사이클 모델, V39). (user_id, species_code) UNIQUE — 한 행이 현재 사이클.
 * - natural_streak: 자연 연속(스탬프) / sparkle_bought_count: 반짝이 구매분 — 둘의 합이 effectiveProgress(stampCount).
 * - last_progress_at: LOST 판정 기준(lastProgressDate + lostAfterDays ≤ today 면 LOST). 일상 기록만 갱신.
 * - cycle_id/cycle_state 와 완료·리셋·되살리기 컬럼은 V39 사이클 모델 — 리셋/되살리기 취소 시 cycle_id 가 바뀐다.
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
    // ── 아프젝 v2 사이클 컬럼 (V39) ──
    @Column(name = "cycle_id", nullable = false)
    var cycleId: UUID = UUID.randomUUID(),
    @Column(name = "cycle_state", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    var cycleState: GrowthCycleState = GrowthCycleState.ACTIVE,
    @Column(name = "completed_kst_date")
    var completedKstDate: LocalDate? = null,
    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
    @Column(name = "reset_due_kst_date")
    var resetDueKstDate: LocalDate? = null,
    @Column(name = "reset_processed_at")
    var resetProcessedAt: LocalDateTime? = null,
    @Column(name = "notify_next", nullable = false)
    var notifyNext: Boolean = false,
    @Column(name = "revive_snoozed_until_kst_date")
    var reviveSnoozedUntilKstDate: LocalDate? = null,
    @Column(name = "lost_at")
    var lostAt: LocalDateTime? = null,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
) {
    /** 이번 사이클 유효 진행(stampCount) = 자연 연속 + 반짝이 구매분. */
    val effectiveProgress: Int
        get() = naturalStreak + sparkleBoughtCount
}

/** 사이클 상태 — API `GrowthItem.cycleState` 와 1:1. */
enum class GrowthCycleState { ACTIVE, LOST, COMPLETED }
