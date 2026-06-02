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
 * N9 (구현 계획서 v4, 2026-05-26): AdMob reward nonce 중복 사용 dedup inbox.
 *
 * Codex audit (Q2/Q5) 반영:
 *   - WebhookInbox (N11) 와 도메인 분리: reward 정책이 billing webhook 과 결합되면
 *     향후 AdMob 외 광고 SDK 추가 시 webhook_inbox 컬럼이 더러워짐
 *   - lifecycle 분리 (created_at / expires_at / consumed_at) — server-issued pending
 *     nonce 도입 시 TTL 검증 가능
 *   - transaction_id 별도 unique partial index — AdMob SSV callback 매핑
 *
 * 외부 AdMob SSV public key 활성화 전에도 client-issued nonce (UUID v4) 의 one-time-use
 * guarantee 만으로 단순 replay attack 차단. SSV signature 검증 (ECDSA P-256 SHA-256) 은
 * 별도 endpoint `/rewards/ad/ssv-callback` 의 raw query string layer — 본 inbox 는 dedup 만.
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
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "expires_at")
    val expiresAt: LocalDateTime? = null,
    @Column(name = "consumed_at", nullable = false, updatable = false)
    val consumedAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val SOURCE_CLIENT = "CLIENT"
        const val SOURCE_SSV_CALLBACK = "SSV_CALLBACK"
    }
}

interface AdRewardNonceInboxRepository : JpaRepository<AdRewardNonceInbox, String> {
    /**
     * N11 WebhookInbox 와 동일 패턴 — 원자적 insert. 같은 nonce 동시 도착 시 한 쪽만
     * 1 (inserted), 다른 쪽 0 (conflict) 반환. check-then-insert race 회피.
     *
     * @return 1 = 신규 nonce / 0 = 이미 사용된 nonce (중복 → NONCE_ALREADY_CONSUMED throw)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox (nonce, user_id, source, consumed_at)
            VALUES (:nonce, :userId, :source, NOW())
            ON CONFLICT (nonce) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("source") source: String,
    ): Int

    /**
     * SSV 콜백 전용 원자적 insert. source='SSV_CALLBACK' 이면 transaction_id 필수
     * (chk_ad_reward_nonce_ssv_tx CHECK) 라 insertIfAbsent 로는 불가 — 본 메서드가 transaction_id 를 함께 INSERT.
     *
     * nonce(PK, ≤64) = transaction_id 의 SHA-256 hex(64) — transaction_id 길이(≤128)가 PK 한계를
     * 넘어도 안전하고 deterministic. dedup 은 nonce PK + uq_ad_reward_nonce_tx_id 양쪽으로 보장되며
     * `ON CONFLICT DO NOTHING` (target 무지정) 이 둘 중 어느 충돌이든 흡수.
     *
     * @return 1 = 신규 SSV transaction / 0 = 이미 처리된 transaction (중복 콜백 — Google 재시도 등)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox (nonce, user_id, source, transaction_id, consumed_at)
            VALUES (:nonceHash, :userId, 'SSV_CALLBACK', :transactionId, NOW())
            ON CONFLICT DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertSsvIfAbsent(
        @Param("nonceHash") nonceHash: String,
        @Param("userId") userId: String,
        @Param("transactionId") transactionId: String,
    ): Int
}
