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

        /** 용도(V39 purpose 컬럼): 광고 보상 지급 / 키우기 되살리기(보상 지급 없음, nonce 소비만). */
        const val PURPOSE_AD_REWARD = "AD_REWARD"
        const val PURPOSE_GROWTH_REVIVE = "GROWTH_REVIVE"
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
     * 아프젝 v2: 용도(purpose) 바인딩 소비 — 키우기 되살리기(GROWTH_REVIVE) 등 광고 보상 지급이 없는 경로.
     * nonce 가 PK 라 AD_REWARD 로 소비된 nonce 를 GROWTH_REVIVE 로 재사용(또는 그 역)하는 것도 0 으로 막힌다.
     * @return 1 = 신규 소비 / 0 = 이미 사용된 nonce
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox (nonce, user_id, source, purpose, consumed_at)
            VALUES (:nonce, :userId, :source, :purpose, NOW())
            ON CONFLICT (nonce) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsentWithPurpose(
        @Param("nonce") nonce: String,
        @Param("userId") userId: String,
        @Param("source") source: String,
        @Param("purpose") purpose: String,
    ): Int

    /**
     * SSV callback 전용 insert — `transaction_id` 컬럼을 실제로 바인딩한다.
     *
     * 기존 insertIfAbsent 로 SSV 를 기록하면 transactionId 가 nonce 슬롯에만 들어가고
     * transaction_id 는 NULL → V21 `chk_ad_reward_nonce_ssv_tx` CHECK 위반으로 정상 콜백마다
     * 실패했다(2026-07-20 audit B2-1, SSV 활성 시 발현). 또한 nonce PK 를 raw transactionId 로
     * 선점하면 향후 client 가 같은 값을 nonce 로 claim 할 때 NONCE_ALREADY_CONSUMED 오충돌이
     * 생기므로 nonce 슬롯은 `ssv:` prefix 로 네임스페이스를 분리한다.
     *
     * ON CONFLICT 무타깃 — nonce PK replay 뿐 아니라 uq_ad_reward_nonce_tx_id(부분 unique)
     * 충돌(예: CLIENT 행이 이미 같은 transaction_id 와 매핑된 경우)도 0 으로 흡수한다.
     *
     * @return 1 = 신규 기록 / 0 = replay 또는 기존 매핑 존재
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO ad_reward_nonce_inbox (nonce, user_id, source, transaction_id, consumed_at)
            VALUES (CONCAT('ssv:', :transactionId), :userId, 'SSV_CALLBACK', :transactionId, NOW())
            ON CONFLICT DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertSsvIfAbsent(
        @Param("transactionId") transactionId: String,
        @Param("userId") userId: String,
    ): Int
}
