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
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 7일 습관 트래커 (낙서장 P1). 연속 기록 → cycle(7일) 완료 시 반짝이 지급.
 * 연장(7/14/21)은 별도 타입 아닌 completedCycles=1/2/3. friendLinkId 연동 시 완료 보상 2배.
 * 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md
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
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    var status: HabitStatus = HabitStatus.ACTIVE,
    @Column(name = "last_checked_date")
    var lastCheckedDate: LocalDate? = null,
    @Column(name = "friend_link_id")
    val friendLinkId: Long? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    // 감사 LOW#23: 동시 checkIn 시 same-day read-check 만으로는 cycle 완료 중복(반짝이 2중 지급) race.
    // @Version 낙관락으로 경합 시 한쪽만 커밋(다른쪽 rollback, 같은 tx 라 credit 도 롤백 → 중복 없음, fail-closed).
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)

enum class HabitStatus { ACTIVE, COMPLETED, BROKEN }

interface HabitTrackerRepository : JpaRepository<HabitTracker, Long> {
    fun findAllByUserIdAndStatus(
        userId: String,
        status: HabitStatus,
    ): List<HabitTracker>

    fun findByIdAndUserId(
        id: Long,
        userId: String,
    ): HabitTracker?

    /** 친구 연동(같은 friend_link_id) 트래커들 — 7일 완료 2배 판정용. */
    fun findAllByFriendLinkId(friendLinkId: Long): List<HabitTracker>

    /**
     * R8: 친구쌍 활성 습관 1개 제한의 원자성 — read-check(findAllByFriendLinkId.any)+save 사이 race 로
     * 동시 생성 시 중복 활성 tracker(→ 상대 1완료로 SPARKLE 2배 이중 판정) 방지. per-(userId|friendLinkId)
     * tx-scoped advisory lock 을 check 직전 획득해 직렬화. (RecordRepository.acquireRecordDailyLock 패턴.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireHabitPairLock(
        @Param("key") key: String,
    ): Int
}
