package com.terraworld.domain.record

import com.terraworld.domain.category.Category
import com.terraworld.domain.user.User
import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "activity_records")
class ActivityRecord(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    val category: Category,
    @Column(columnDefinition = "TEXT")
    val memo: String? = null,
    @Column(name = "duration_minutes")
    val durationMinutes: Int? = null,
    @Column(name = "photo_url", length = 2048)
    val photoUrl: String? = null,
    @Column(name = "recorded_date", nullable = false)
    val recordedDate: LocalDate,
    // 낙서장 P1: 기록 습관/일상 분리. 기본 DAILY (기존 카테고리 기록 = 일상).
    @Column(name = "kind", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    val kind: RecordKind = RecordKind.DAILY,
    @Column(name = "daily_type", length = 16)
    @Enumerated(EnumType.STRING)
    val dailyType: DailyType? = null,
    @Column(name = "habit_tracker_id")
    val habitTrackerId: Long? = null,
    @Column(name = "reward_granted", nullable = false)
    val rewardGranted: Boolean = true,
    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,
    @Column(name = "partner_user_id", length = 255)
    val partnerUserId: String? = null,
    @Column(name = "joint_session_id")
    val jointSessionId: java.util.UUID? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
