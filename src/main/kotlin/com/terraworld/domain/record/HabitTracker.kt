package com.terraworld.domain.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.Version
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 7일 습관 트래커 (낙서장 P1 → 아프젝 v2 상태머신, V39).
 *
 * 상태: PENDING(친구 요청 수락 대기) → ACTIVE(진행) → COMPLETED_UNCLAIMED(7일째 체크인, 보상 미수령)
 *   → COMPLETED(complete 로 보상 수령·종료) 또는 extend 로 새 사이클(ACTIVE/PENDING).
 *   BROKEN 은 중단/거절/취소/만료 종료.
 * 친구 습관은 요청자·수신자 각각 트래커 1개(미러)를 가지며 [partnerTrackerId] 로 서로를 가리킨다.
 * 사이클 원장은 [HabitCycle], 요청 원장은 [HabitPairRequest] — partnerStatus/extendStatus 는 그 파생 뷰.
 * 설계 SoT: docs/root/plans/2026-08-23-apjek-v2-api-contract.md §R2
 */
@Entity
@Table(name = "habit_trackers")
class HabitTracker(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false, length = 64)
    val userId: String = "",
    @Column(nullable = false, length = 100)
    var title: String = "",
    @Column(name = "start_date", nullable = false)
    val startDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "current_streak_days", nullable = false)
    var currentStreakDays: Int = 0,
    @Column(name = "cycle_length_days", nullable = false)
    val cycleLengthDays: Int = 7,
    @Column(name = "completed_cycles", nullable = false)
    var completedCycles: Int = 0,
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    var status: HabitStatus = HabitStatus.ACTIVE,
    @Column(name = "last_checked_date")
    var lastCheckedDate: LocalDate? = null,
    // 친구 연동 ID(= 수락된 invite.id). 상대가 종료된 뒤 solo 로 연장하면 해제한다.
    @Column(name = "friend_link_id")
    var friendLinkId: Long? = null,
    // 아프젝 v2: 상대(미러) 트래커 id — 양측이 서로를 가리킨다. solo 면 null.
    @Column(name = "partner_tracker_id")
    var partnerTrackerId: Long? = null,
    // 아프젝 v2: 현재 진행 사이클(habit_cycles.id). PENDING(수락 전) 이면 null 일 수 있다.
    @Column(name = "current_cycle_id")
    var currentCycleId: Long? = null,
    // 아프젝 v2: 7일째 체크인 시각(완주). complete/extend 로 사이클이 닫히면 null.
    @Column(name = "cycle_completed_at")
    var cycleCompletedAt: LocalDateTime? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 감사 LOW#23: 동시 checkIn 시 same-day read-check 만으로는 cycle 완료 중복 race.
    // @Version 낙관락으로 경합 시 한쪽만 커밋(다른쪽 rollback, fail-closed).
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

enum class HabitStatus {
    PENDING,
    ACTIVE,
    COMPLETED_UNCLAIMED,
    COMPLETED,
    BROKEN,
    ;

    companion object {
        /** 목록 노출 + 활성 1개 제한 판정 집합 (PENDING/ACTIVE/COMPLETED_UNCLAIMED). */
        val OPEN: Set<HabitStatus> = setOf(PENDING, ACTIVE, COMPLETED_UNCLAIMED)
    }
}

interface HabitTrackerRepository : JpaRepository<HabitTracker, Long> {
    fun findAllByUserIdAndStatusIn(
        userId: String,
        statuses: Collection<HabitStatus>,
    ): List<HabitTracker>

    fun countByUserIdAndStatusIn(
        userId: String,
        statuses: Collection<HabitStatus>,
    ): Long

    fun findByIdAndUserId(
        id: Long,
        userId: String,
    ): HabitTracker?

    /**
     * 활성 1개 제한(createTracker)의 원자성 — count-check + insert 사이 race 로 동시 POST /habits 가
     * 둘 다 제한 미만을 보고 중복 생성하는 것을 per-user tx-scoped advisory lock 으로 직렬화.
     * (RecordRepository.acquireRecordDailyLock 패턴.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireHabitPairLock(
        @Param("key") key: String,
    ): Int

    /**
     * 조건부 상태 전이(CAS) — 동시 요청에도 한쪽만 rows==1. version 도 함께 올린다 (bulk UPDATE 는 @Version 우회):
     * 증가 없이는 "체크인이 ACTIVE/version=N 을 읽음 → 전이 커밋 → 체크인 저장" 순서에서 낙관락이 경합을 못 본다.
     * 반환 0 = 이미 다른 상태이거나 미존재/타인.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE HabitTracker t SET t.status = :to, t.version = t.version + 1 " +
            "WHERE t.id = :id AND t.userId = :userId AND t.status = :from",
    )
    fun casStatus(
        @Param("id") id: Long,
        @Param("userId") userId: String,
        @Param("from") from: HabitStatus,
        @Param("to") to: HabitStatus,
    ): Int

    /**
     * 중단(BROKEN) 조건부 전환 — 동시 DELETE 에도 낙관락 충돌 없이 멱등 (Codex R1 #8).
     * 아프젝 v2: ACTIVE 뿐 아니라 열린 상태(PENDING/COMPLETED_UNCLAIMED) 전부 대상. 반환 0 = 이미 종료/미존재/타인.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE HabitTracker t SET t.status = com.terraworld.domain.record.HabitStatus.BROKEN, t.version = t.version + 1 " +
            "WHERE t.id = :id AND t.userId = :userId AND t.status IN (" +
            "com.terraworld.domain.record.HabitStatus.PENDING, " +
            "com.terraworld.domain.record.HabitStatus.ACTIVE, " +
            "com.terraworld.domain.record.HabitStatus.COMPLETED_UNCLAIMED)",
    )
    fun markBroken(
        @Param("id") id: Long,
        @Param("userId") userId: String,
    ): Int
}
