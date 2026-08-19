package com.terraworld.domain.notification

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.UUID

/**
 * 인앱 알림함 항목 (apjek social loop, V38).
 *
 * 푸시(FCM)는 발송 즉시 휘발 — 본 엔티티가 앱 내에서 다시 볼 수 있는 영속 저장 경로다.
 * 저장은 [com.terraworld.api.notification.NotificationEventListener] 가 도메인 이벤트의
 * 커밋 확정 후(@Async AFTER_COMMIT) 수행 — 푸시 발송(FcmEventListener)과 별개 경로.
 * 읽음 상태는 readAt 타임스탬프 (NULL = 미읽음).
 */
@Entity
@Table(name = "user_notifications")
class UserNotification(
    // API 스펙 확정안 기준 UUID pk — Hibernate 6 클라이언트 측 생성 (DB 왕복 없음).
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    val id: UUID? = null,
    @Column(name = "user_id", nullable = false)
    val userId: String = "",
    @Column(nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    val type: NotificationType = NotificationType.SYSTEM,
    @Column(nullable = false, length = 100)
    val title: String = "",
    @Column(nullable = false, length = 500)
    val body: String = "",
    /** 알림 탭 시 이동할 클라이언트 경로 (예: /record, /friends) — 없으면 null. */
    @Column(length = 200)
    val route: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "read_at")
    var readAt: LocalDateTime? = null,
)

/** 알림 타입 — API 스펙 확정안의 enum 과 1:1 (DB CHECK 제약과도 동일 집합). */
enum class NotificationType { SPIRIT_ARRIVED, PAYMENT, FRIEND_JOINED, CHEER, SYSTEM }

interface UserNotificationRepository : JpaRepository<UserNotification, UUID> {
    /** 알림함 목록 — user_id 기준 최신순 페이지 조회 (idx_user_notifications_user_created). */
    fun findAllByUserIdOrderByCreatedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<UserNotification>

    /** 미읽음 카운트 — 뱃지 표시용. */
    fun countByUserIdAndReadAtIsNull(userId: String): Long

    /**
     * 지정 ids 읽음 처리 — user_id 를 함께 걸어 타인 알림 읽음 처리를 차단한다.
     * 이미 읽은 항목(readAt IS NOT NULL)은 건드리지 않아 최초 읽음 시각이 보존된다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE UserNotification n
        SET n.readAt = :now
        WHERE n.userId = :userId AND n.id IN :ids AND n.readAt IS NULL
        """,
    )
    fun markRead(
        @Param("userId") userId: String,
        @Param("ids") ids: Collection<UUID>,
        @Param("now") now: LocalDateTime,
    ): Int

    /** 전체 읽음 처리 — markRead(빈 ids) 요청 시 사용. */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE UserNotification n
        SET n.readAt = :now
        WHERE n.userId = :userId AND n.readAt IS NULL
        """,
    )
    fun markAllRead(
        @Param("userId") userId: String,
        @Param("now") now: LocalDateTime,
    ): Int
}
