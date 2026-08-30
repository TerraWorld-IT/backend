package com.terraworld.domain.reward

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
 * 광고 보상 nonce 수명주기 inbox.
 *
 * legacy/shadow 에서는 기존 CLIENT nonce 를 즉시 소비할 수 있다. authoritative 에서는 서버가
 * 발급한 SERVER nonce 만 PENDING -> VERIFIED(서명 검증 SSV) -> CONSUMED 순서로 전이한다.
 * transaction_id unique 제약과 nonce PK가 SSV 및 client 재시도를 각각 한 번으로 제한한다.
 */
@Entity
@Table(name = "ad_reward_nonce_inbox")
class AdRewardNonceInbox(
    @Id
    @Column(name = "nonce", length = 64)
    val nonce: String,
    @Column(name = "user_id", nullable = false, length = 64)
    val userId: String,
    @Column(name = "source", nullable = false, length = 16)
    val source: String = SOURCE_CLIENT,
    @Column(name = "transaction_id", length = 128)
    val transactionId: String? = null,
    @Column(name = "purpose", nullable = false, length = 32)
    val purpose: String = PURPOSE_AD_REWARD,
    @Column(name = "status", nullable = false, length = 16)
    val status: String = STATUS_CONSUMED,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,
    @Column(name = "verified_at")
    val verifiedAt: LocalDateTime? = null,
    @Column(name = "consumed_at")
    val consumedAt: LocalDateTime? = LocalDateTime.now(),
) {
    companion object {
        const val SOURCE_CLIENT = "CLIENT"
        const val SOURCE_SSV_CALLBACK = "SSV_CALLBACK"
        const val SOURCE_SERVER = "SERVER"

        const val PURPOSE_AD_REWARD = "AD_REWARD"
        const val PURPOSE_GROWTH_REVIVE = "GROWTH_REVIVE"

        const val STATUS_PENDING = "PENDING"
        const val STATUS_VERIFIED = "VERIFIED"
        const val STATUS_CONSUMED = "CONSUMED"
        const val STATUS_EXPIRED = "EXPIRED"
    }
}

interface AdRewardNonceInboxRepository : JpaRepository<AdRewardNonceInbox, String> {
    /** 같은 사용자·용도의 nonce 발급을 직렬화한다. */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireIssueLock(
        @Param("key") key: String,
    ): Int

    /** 같은 SSV transaction 처리와 transaction_id unique 확인을 직렬화한다. */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireSsvTransactionLock(
        @Param("key") key: String,
    ): Int

    @Modifying
    @Query(
        value =
            """
            UPDATE ad_reward_nonce_inbox
               SET status = 'EXPIRED'
             WHERE user_id = :userId
               AND purpose = :purpose
               AND source = 'SERVER'
               AND status IN ('PENDING', 'VERIFIED')
               AND expires_at < :now
            """,
        nativeQuery = true,
    )
    fun expireIssued(
        @Param("userId") userId: String,
        @Param("purpose") purpose: String,
        @Param("now") now: LocalDateTime,
    ): Int

    @Query(
        """
        SELECT nonce
          FROM AdRewardNonceInbox nonce
         WHERE nonce.userId = :userId
           AND nonce.purpose = :purpose
           AND nonce.source = 'SERVER'
           AND nonce.status IN ('PENDING', 'VERIFIED')
           AND nonce.expiresAt >= :now
         ORDER BY nonce.createdAt DESC
        """,
    )
    fun findActiveIssued(
        @Param("userId") userId: String,
        @Param("purpose") purpose: String,
        @Param("now") now: LocalDateTime,
    ): List<AdRewardNonceInbox>

    /** legacy/shadow 클라이언트 nonce 를 원자적으로 즉시 소비한다. */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox
                (nonce, user_id, source, purpose, status, consumed_at)
            VALUES
                (:nonce, :userId, :source, :purpose, 'CONSUMED', NOW())
            ON CONFLICT (nonce) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertLegacyIfAbsent(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("source") source: String,
        @Param("purpose") purpose: String,
    ): Int

    /** legacy/shadow 에서 서버 발급 nonce 를 SSV보다 먼저 사용할 수 있게 한다. */
    @Modifying
    @Query(
        value =
            """
            UPDATE ad_reward_nonce_inbox
               SET status = 'CONSUMED', consumed_at = :now
             WHERE nonce = :nonce
               AND user_id = :userId
               AND purpose = :purpose
               AND source = 'SERVER'
               AND status IN ('PENDING', 'VERIFIED')
               AND consumed_at IS NULL
               AND expires_at >= :now
            """,
        nativeQuery = true,
    )
    fun consumeIssuedWithoutVerification(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("purpose") purpose: String,
        @Param("now") now: LocalDateTime,
    ): Int

    /** authoritative 에서 SSV 검증 완료 nonce 만 소비한다. */
    @Modifying
    @Query(
        value =
            """
            UPDATE ad_reward_nonce_inbox
               SET status = 'CONSUMED', consumed_at = :now
             WHERE nonce = :nonce
               AND user_id = :userId
               AND purpose = :purpose
               AND source = 'SERVER'
               AND status = 'VERIFIED'
               AND verified_at IS NOT NULL
               AND transaction_id IS NOT NULL
               AND consumed_at IS NULL
               AND expires_at >= :now
            """,
        nativeQuery = true,
    )
    fun consumeVerifiedIssued(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("purpose") purpose: String,
        @Param("now") now: LocalDateTime,
    ): Int

    /**
     * 서명 검증을 통과한 SSV transaction 을 서버 발급 nonce 에 결합한다.
     * shadow 에서 이미 소비된 SERVER nonce 는 상태를 되돌리지 않고 검증 시각만 기록한다.
     */
    @Modifying
    @Query(
        value =
            """
            UPDATE ad_reward_nonce_inbox
               SET transaction_id = :transactionId,
                   verified_at = COALESCE(verified_at, :now),
                   status = CASE WHEN status = 'PENDING' THEN 'VERIFIED' ELSE status END
             WHERE nonce = :nonce
               AND user_id = :userId
               AND source = 'SERVER'
               AND (
                    (transaction_id = :transactionId AND verified_at IS NOT NULL)
                    OR (
                        transaction_id IS NULL
                        AND expires_at >= :now
                        AND status IN ('PENDING', 'CONSUMED')
                    )
               )
            """,
        nativeQuery = true,
    )
    fun verifyIssued(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("transactionId") transactionId: String,
        @Param("now") now: LocalDateTime,
    ): Int

    fun findByTransactionId(transactionId: String): AdRewardNonceInbox?

    /** 서버 발급 nonce 와 결합되지 않은 정상 SSV도 transaction dedup 감사 행으로 남긴다. */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox
                (nonce, user_id, source, transaction_id, purpose, status, verified_at, consumed_at)
            VALUES
                (:syntheticNonce, :userId, 'SSV_CALLBACK', :transactionId, 'AD_REWARD', 'CONSUMED', NOW(), NOW())
            ON CONFLICT DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertDetachedSsvIfAbsent(
        @Param("syntheticNonce") syntheticNonce: String,
        @Param("transactionId") transactionId: String,
        @Param("userId") userId: String,
    ): Int
}
