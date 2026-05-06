package com.terraworld.domain.userdevice

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
}

enum class DevicePlatform { ANDROID, IOS, WEB }
