package com.terraworld.domain.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 습관 7일 사이클 원장 (아프젝 v2, V39). 트래커당 사이클 1행 — 7일째 체크인 시 completedAt,
 * complete/extend 지급 시 rewardClaimedAt/rewardSparkle. 지급 멱등의 SoT 는 user_grants 의
 * idempotency_key `habit:{cycleId}:reward:{userId}` 이며 본 원장은 그 결과를 기록한다.
 */
@Entity
@Table(name = "habit_cycles")
class HabitCycle(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "tracker_id", nullable = false)
    val trackerId: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String = "",
    @Column(name = "cycle_no", nullable = false)
    val cycleNo: Int = 1,
    @Column(name = "started_on", nullable = false)
    val startedOn: LocalDate = LocalDate.EPOCH,
    @Column(name = "completed_at")
    var completedAt: LocalDateTime? = null,
    @Column(name = "reward_claimed_at")
    var rewardClaimedAt: LocalDateTime? = null,
    @Column(name = "reward_sparkle", nullable = false)
    var rewardSparkle: Long = 0,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        /** 보상 지급 원장 키 (user_grants.idempotency_key). */
        fun rewardKey(
            cycleId: Long,
            userId: String,
        ): String = "habit:$cycleId:reward:$userId"
    }
}

interface HabitCycleRepository : JpaRepository<HabitCycle, Long>
