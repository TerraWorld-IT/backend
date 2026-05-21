package com.terraworld.domain.terrarium

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * Codex audit Q4 (구현 계획서 v4, 2026-05-21): 시들기/출석 알림 발송 마커.
 *
 * WiltScheduler 가 단계별 알림을 1회만 발송하도록 추적. 사용자가 다시 기록하면 (days == 0)
 * 해당 user 의 마커가 전체 삭제되어 다음 시들기 사이클에 재알림 가능.
 *
 * marker_type: WILT_1 / WILT_2 / WILT_3 (시들기 3단계) / ATTENDANCE_1 (미기록 nudge)
 */
@Entity
@Table(name = "wilt_notification_marker")
class WiltNotificationMarker(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String,
    @Column(name = "marker_type", nullable = false, length = 32)
    val markerType: String,
    @Column(name = "notified_at", nullable = false, updatable = false)
    val notifiedAt: LocalDateTime = LocalDateTime.now(),
)

interface WiltNotificationMarkerRepository : JpaRepository<WiltNotificationMarker, Long> {
    fun existsByUserIdAndMarkerType(
        userId: String,
        markerType: String,
    ): Boolean

    fun deleteByUserId(userId: String)
}
