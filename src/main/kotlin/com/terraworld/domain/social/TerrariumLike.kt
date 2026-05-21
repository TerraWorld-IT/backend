package com.terraworld.domain.social

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime
import java.util.Optional

/**
 * P-FRIEND-001 (구현 계획서 v4, 2026-05-21): 친구 테라리움 좋아요.
 *
 * liker_user_id × target_user_id 1행 (unique). 좋아요는 토글 — 이미 있으면 삭제(취소),
 * 없으면 추가. 친구 관계 (수락된 invite) 인 경우에만 허용.
 */
@Entity
@Table(name = "terrarium_like")
class TerrariumLike(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "liker_user_id", nullable = false, length = 255)
    val likerUserId: String,
    @Column(name = "target_user_id", nullable = false, length = 255)
    val targetUserId: String,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

interface TerrariumLikeRepository : JpaRepository<TerrariumLike, Long> {
    fun findByLikerUserIdAndTargetUserId(
        likerUserId: String,
        targetUserId: String,
    ): Optional<TerrariumLike>

    fun countByTargetUserId(targetUserId: String): Long
}
