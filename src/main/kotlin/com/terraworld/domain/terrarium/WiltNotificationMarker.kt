package com.terraworld.domain.terrarium

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * Codex audit Q4 (구현 계획서 v4, 2026-05-21): 시들기/출석 알림 발송 마커.
 *
 * WiltScheduler 가 단계별 알림을 비활동 주기당 1회만 발송하도록 추적. 마커는 "현재 진행
 * 중인 비활동 주기" 에만 존재한다 (R4-WILT, 2026-07-15): 사용자가 다시 기록/복구하면 다음
 * 스캔이 이전 주기 마커(최신 notifiedAt <= 마지막 활동일, KST 날짜 비교)를 전체 삭제해
 * 다음 시들기 사이클에 재알림한다.
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

    /**
     * BE-05 (2026-07-15 성능 감사): WiltScheduler 배치용 — chunk 유저 집합의 마커를
     * IN 1쿼리로 일괄 로드 후 메모리 diff (유저×마커타입 existsBy 루프 대체).
     * 호출 전 userIds 비어있지 않음을 보장할 것 (빈 IN 절 방지).
     */
    fun findAllByUserIdIn(userIds: Collection<String>): List<WiltNotificationMarker>

    /**
     * BE-05: 오늘 기록/복구한 유저들의 마커 일괄 삭제 — 유저별 deleteByUserId 루프
     * (derived delete 는 SELECT 후 행별 DELETE) 대신 벌크 1 statement.
     */
    @Modifying
    @Query("DELETE FROM WiltNotificationMarker m WHERE m.userId IN :userIds")
    fun deleteAllByUserIdIn(
        @Param("userIds") userIds: Collection<String>,
    ): Int
}
