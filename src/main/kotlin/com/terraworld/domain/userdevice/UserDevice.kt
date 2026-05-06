package com.terraworld.domain.userdevice

import com.fasterxml.jackson.annotation.JsonIgnore
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
    name = "user_devices",
    uniqueConstraints = [UniqueConstraint(name = "user_devices_user_token_unique", columnNames = ["user_id", "token"])],
    indexes = [
        Index(name = "idx_user_devices_user_active", columnList = "user_id"),
        Index(name = "idx_user_devices_platform_active", columnList = "platform"),
    ],
)
class UserDevice(
    @Column(name = "user_id", nullable = false)
    val userId: String,
    /**
     * SEC-005: FCM/APNs 토큰은 "이 디바이스로 push 보낼 수 있는" capability 와 동일.
     * - JSON 직렬화 차단 (`@JsonIgnore`) — 응답/audit 어디로도 노출되지 않게.
     * - toString 에서도 가림 (override 아래) — log 실수 차단.
     * - 분석/식별이 필요한 곳은 `tokenFingerprint()` 사용.
     */
    @JsonIgnore
    @Column(name = "token", nullable = false, columnDefinition = "TEXT")
    var token: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "platform", nullable = false, length = 16)
    var platform: DevicePlatform,
    @Column(name = "app_version", length = 32)
    var appVersion: String? = null,
    @Column(name = "last_seen_at", nullable = false)
    var lastSeenAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0

    /** Last-N-chars + length fingerprint — 로그/audit 에 노출 안전한 식별자. */
    fun tokenFingerprint(): String {
        val tail = token.takeLast(6)
        return "len=${token.length},…$tail"
    }

    override fun toString(): String =
        "UserDevice(id=$id, userId='$userId', platform=$platform, appVersion=$appVersion, " +
            "isActive=$isActive, lastSeenAt=$lastSeenAt, token=${tokenFingerprint()})"
}

enum class DevicePlatform { ANDROID, IOS, WEB }
