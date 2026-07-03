package com.terraworld.domain.character

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * 유저 보유 캐릭터(정령). (user_id, character_code) UNIQUE — 중복 소유 방지(멱등).
 */
@Entity
@Table(name = "user_characters")
class UserCharacter(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "character_code", nullable = false, length = 32)
    val characterCode: String,
    @Column(name = "acquired_via", nullable = false, length = 24)
    val acquiredVia: String,
    @Column(name = "acquired_at", nullable = false, updatable = false)
    val acquiredAt: LocalDateTime = LocalDateTime.now(),
)
