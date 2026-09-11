package com.terraworld.domain.upload

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/** 사용자 행이 삭제된 뒤에도 사진 정리 대상과 실패 횟수를 보존한다. */
@Entity
@Table(name = "photo_deletion_outbox")
class PhotoDeletionOutbox(
    @Id
    @Column(name = "public_url", columnDefinition = "TEXT")
    val publicUrl: String,
    @Column(name = "attempts", nullable = false)
    var attempts: Int = 0,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "last_attempt_at")
    var lastAttemptAt: LocalDateTime? = null,
)

interface PhotoDeletionOutboxRepository : JpaRepository<PhotoDeletionOutbox, String> {
    fun findByAttemptsLessThanOrderByCreatedAtAscPublicUrlAsc(
        maxAttempts: Int,
        pageable: Pageable,
    ): List<PhotoDeletionOutbox>

    // afterCommit의 기존 영속성 컨텍스트에 쓰기가 묶이지 않도록 별도 커밋한다.
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query("DELETE FROM PhotoDeletionOutbox p WHERE p.publicUrl = :publicUrl")
    fun deleteCompleted(publicUrl: String): Int

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    @Modifying
    @Query(
        "UPDATE PhotoDeletionOutbox p SET p.attempts = p.attempts + 1, p.lastAttemptAt = :now " +
            "WHERE p.publicUrl = :publicUrl AND p.attempts < :maxAttempts",
    )
    fun recordFailure(
        publicUrl: String,
        now: LocalDateTime,
        maxAttempts: Int,
    ): Int
}
