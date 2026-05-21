package com.terraworld.domain.billing

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * N11 (구현 계획서 v4, 2026-05-21): webhook notification 중복 수신 dedup inbox.
 *
 * Google Play RTDN 은 Pub/Sub at-least-once delivery 라 동일 notification 이 2회 이상
 * 도착 가능. 본 inbox 에 notification_id 가 이미 있으면 webhook handler 가 재처리 skip.
 */
@Entity
@Table(name = "webhook_inbox")
class WebhookInbox(
    @Id
    @Column(name = "notification_id", length = 255)
    val notificationId: String,
    @Column(name = "notification_type", nullable = false, length = 64)
    val notificationType: String,
    @Column(name = "received_at", nullable = false, updatable = false)
    val receivedAt: LocalDateTime = LocalDateTime.now(),
)

interface WebhookInboxRepository : JpaRepository<WebhookInbox, String> {
    /**
     * Codex audit Q3 (구현 계획서 v4): check-then-insert race 회피 — 원자적 insert.
     * 동일 notification_id 동시 도착 시 한 쪽만 1 (inserted), 다른 쪽 0 (conflict) 반환.
     * @return 1 = 신규 insert / 0 = 이미 처리됨 (중복)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO webhook_inbox (notification_id, notification_type, received_at)
            VALUES (:notificationId, :notificationType, NOW())
            ON CONFLICT (notification_id) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("notificationId") notificationId: String,
        @Param("notificationType") notificationType: String,
    ): Int
}
