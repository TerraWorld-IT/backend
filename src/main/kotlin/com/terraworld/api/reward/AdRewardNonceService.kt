package com.terraworld.api.reward

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

data class IssuedAdRewardNonce(
    val nonce: String,
    val purpose: String,
    val status: String,
    val expiresAt: LocalDateTime,
)

enum class SsvNonceResult {
    RECORDED,
    VERIFIED,
    DUPLICATE,
    UNMATCHED,
}

data class SsvNonceOutcome(
    val result: SsvNonceResult,
    val purpose: String? = null,
)

@Service
class AdRewardNonceService(
    private val repository: AdRewardNonceInboxRepository,
    private val policy: AdRewardPolicy,
) {
    companion object {
        val NONCE_TTL: Duration = Duration.ofMinutes(10)

        private const val MIN_NONCE_LENGTH = 16
        private const val MAX_NONCE_LENGTH = 64
        private const val MAX_TRANSACTION_ID_LENGTH = 128
        private const val ISSUE_LOCK_PREFIX = "ad-reward-nonce-issue|"
        private const val SSV_LOCK_PREFIX = "ad-reward-ssv|"
    }

    fun isAuthoritative(): Boolean = policy.mode == AdRewardMode.AUTHORITATIVE

    fun modeValue(): String = policy.mode.configValue

    fun requirePurpose(value: String): String =
        when (value) {
            AdRewardNonceInbox.PURPOSE_AD_REWARD,
            AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE,
            -> value
            else -> throw BusinessException(ErrorCode.INVALID_INPUT, "지원하지 않는 광고 nonce 용도입니다")
        }

    /** 사용자·용도별 유효한 nonce 하나를 반환하며, 없으면 고정 TTL로 새로 발급한다. */
    @Transactional
    fun issue(
        userId: String,
        purpose: String,
    ): IssuedAdRewardNonce {
        val checkedPurpose = requirePurpose(purpose)
        val now = LocalDateTime.now(ZoneOffset.UTC)
        repository.acquireIssueLock("$ISSUE_LOCK_PREFIX$userId|$checkedPurpose")
        repository.expireIssued(userId, checkedPurpose, now)

        val existing = repository.findActiveIssued(userId, checkedPurpose, now).firstOrNull()
        if (existing != null) return existing.toIssued()

        val expiresAt = now.plus(NONCE_TTL)
        val issued =
            repository.saveAndFlush(
                AdRewardNonceInbox(
                    nonce = UUID.randomUUID().toString(),
                    userId = userId,
                    source = AdRewardNonceInbox.SOURCE_SERVER,
                    purpose = checkedPurpose,
                    status = AdRewardNonceInbox.STATUS_PENDING,
                    createdAt = now,
                    expiresAt = expiresAt,
                    consumedAt = null,
                ),
            )
        return issued.toIssued()
    }

    /** 현재 단계 정책에 따라 client nonce 또는 SSV 검증 완료 server nonce 를 한 번만 소비한다. */
    @Transactional
    fun consume(
        userId: String,
        nonce: String?,
        purpose: String,
        legacySource: String = AdRewardNonceInbox.SOURCE_CLIENT,
    ): String {
        val checkedNonce = requireNonce(nonce)
        val checkedPurpose = requirePurpose(purpose)
        val now = LocalDateTime.now(ZoneOffset.UTC)

        val consumedSource =
            if (isAuthoritative()) {
                if (repository.consumeVerifiedIssued(checkedNonce, userId, checkedPurpose, now) == 1) {
                    AdRewardNonceInbox.SOURCE_SERVER
                } else {
                    null
                }
            } else {
                val inserted = repository.insertLegacyIfAbsent(checkedNonce, userId, legacySource, checkedPurpose)
                when {
                    inserted == 1 -> legacySource
                    repository.consumeIssuedWithoutVerification(checkedNonce, userId, checkedPurpose, now) == 1 ->
                        AdRewardNonceInbox.SOURCE_SERVER
                    else -> null
                }
            }

        if (consumedSource != null) return consumedSource

        val existing = repository.findById(checkedNonce).orElse(null)
        if (existing?.status == AdRewardNonceInbox.STATUS_CONSUMED) {
            throw BusinessException(ErrorCode.NONCE_ALREADY_CONSUMED)
        }
        if (isAuthoritative()) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "SSV 검증을 완료한 서버 발급 nonce 가 필요합니다")
        }
        throw BusinessException(ErrorCode.NONCE_ALREADY_CONSUMED)
    }

    /** 정상 서명 SSV를 transaction 단위로 dedup하고, shadow/authoritative에서는 서버 nonce와 결합한다. */
    @Transactional
    fun recordVerifiedSsv(
        userId: String,
        transactionId: String,
        customData: String?,
    ): SsvNonceOutcome {
        if (transactionId.isBlank() || transactionId.length > MAX_TRANSACTION_ID_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "SSV transaction_id 형식이 올바르지 않습니다")
        }

        repository.acquireSsvTransactionLock("$SSV_LOCK_PREFIX$transactionId")
        val recorded = repository.findByTransactionId(transactionId)
        if (recorded != null) {
            val matches =
                recorded.source == AdRewardNonceInbox.SOURCE_SERVER &&
                    recorded.nonce == customData &&
                    recorded.userId == userId
            return SsvNonceOutcome(
                result = SsvNonceResult.DUPLICATE,
                purpose = recorded.purpose.takeIf { matches },
            )
        }

        if (policy.mode != AdRewardMode.LEGACY && isNonceShapeValid(customData)) {
            val verified = repository.verifyIssued(customData!!, userId, transactionId, LocalDateTime.now(ZoneOffset.UTC))
            if (verified == 1) {
                val purpose = repository.findById(customData).orElse(null)?.purpose
                return SsvNonceOutcome(SsvNonceResult.VERIFIED, purpose)
            }
        }

        repository.insertDetachedSsvIfAbsent(syntheticNonce(transactionId), transactionId, userId)
        return if (policy.mode == AdRewardMode.LEGACY) {
            SsvNonceOutcome(SsvNonceResult.RECORDED)
        } else {
            SsvNonceOutcome(SsvNonceResult.UNMATCHED)
        }
    }

    private fun requireNonce(nonce: String?): String {
        if (!isNonceShapeValid(nonce)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "광고 시청 nonce 가 필요합니다")
        }
        return nonce!!
    }

    private fun isNonceShapeValid(nonce: String?): Boolean {
        val length = nonce?.length ?: return false
        return nonce.isNotBlank() && length in MIN_NONCE_LENGTH..MAX_NONCE_LENGTH
    }

    private fun syntheticNonce(transactionId: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(transactionId.toByteArray(Charsets.UTF_8))
        return "ssv:" + Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun AdRewardNonceInbox.toIssued(): IssuedAdRewardNonce =
        IssuedAdRewardNonce(
            nonce = nonce,
            purpose = purpose,
            status = status,
            expiresAt = requireNotNull(expiresAt),
        )
}
