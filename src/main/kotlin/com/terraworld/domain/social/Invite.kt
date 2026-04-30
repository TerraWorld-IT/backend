package com.terraworld.domain.social

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "invites")
class Invite(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, unique = true, length = 20)
    val code: String,
    @Column(name = "inviter_user_id", nullable = false)
    val inviterUserId: String,
    @Column(name = "invitee_user_id")
    var inviteeUserId: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime,
    @Column(name = "accepted_at")
    var acceptedAt: LocalDateTime? = null,
) {
    fun isExpired(now: LocalDateTime = LocalDateTime.now()): Boolean = now.isAfter(expiresAt)

    fun isAccepted(): Boolean = acceptedAt != null
}
