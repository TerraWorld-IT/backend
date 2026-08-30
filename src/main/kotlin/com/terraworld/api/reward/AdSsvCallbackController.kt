package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * AdMob rewarded ad SSV 공개 GET callback.
 *
 * raw query 순서를 보존한 ECDSA 검증 뒤 transaction_id 를 dedup한다. shadow/authoritative 에서는
 * 서명된 custom_data 를 서버 발급 nonce 와 결합하고, 실제 보상·AD revive 는 인증된 client 요청이
 * VERIFIED nonce 를 소비할 때 수행한다.
 */
@RestController
@RequestMapping("/api/v1/rewards/ad")
@Hidden
class AdSsvCallbackController(
    private val verifier: AdSsvSignatureVerifier,
    private val nonceService: AdRewardNonceService,
    private val auditService: AuditService,
    private val policy: AdRewardPolicy,
    @Value("\${reward.ad.ssv.timestamp-tolerance-ms:300000}")
    private val timestampToleranceMs: Long,
    @Value("\${reward.ad.ssv.ad-unit-allowlist:}")
    adUnitAllowlistCsv: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val adUnitAllowlist =
        adUnitAllowlistCsv
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toSet()

    init {
        require(timestampToleranceMs > 0) { "reward.ad.ssv.timestamp-tolerance-ms 는 양수여야 합니다" }
    }

    @GetMapping("/ssv-callback")
    fun handleSsv(request: HttpServletRequest): ResponseEntity<Void> {
        val content = signedContent(request.queryString)
        if (content == null) {
            log.warn("event=ad.ssv.malformed reason=raw-query-layout mode={}", policy.mode.configValue)
            return ResponseEntity.badRequest().build()
        }

        val signature = request.getParameter("signature")
        val keyId = request.getParameter("key_id")?.toLongOrNull()
        val transactionId = request.getParameter("transaction_id")
        val userId = request.getParameter("user_id")
        val timestamp = request.getParameter("timestamp")?.toLongOrNull()
        val adUnit = request.getParameter("ad_unit")
        val customData = request.getParameter("custom_data")

        if (
            signature.isNullOrBlank() ||
            signature.length > MAX_SIGNATURE_LENGTH ||
            keyId == null ||
            transactionId.isNullOrBlank() ||
            transactionId.length > MAX_TRANSACTION_ID_LENGTH ||
            userId.isNullOrBlank() ||
            userId.length > MAX_USER_ID_LENGTH ||
            timestamp == null
        ) {
            log.warn("event=ad.ssv.malformed reason=missing-or-invalid-param mode={}", policy.mode.configValue)
            return ResponseEntity.badRequest().build()
        }

        val now = System.currentTimeMillis()
        if (timestamp < now - timestampToleranceMs || timestamp > now + timestampToleranceMs) {
            log.warn("event=ad.ssv.stale-timestamp mode={} timestamp={}", policy.mode.configValue, timestamp)
            return ResponseEntity.status(403).build()
        }

        if (policy.mode == AdRewardMode.AUTHORITATIVE && adUnitAllowlist.isEmpty()) {
            log.error("event=ad.ssv.configuration-error field=adUnitAllowlist mode=authoritative")
            return ResponseEntity.status(503).build()
        }
        if (adUnitAllowlist.isNotEmpty() && (adUnit == null || adUnit !in adUnitAllowlist)) {
            log.warn("event=ad.ssv.ad-unit-not-allowed mode={} adUnit={}", policy.mode.configValue, adUnit)
            return ResponseEntity.status(403).build()
        }

        if (!verifier.verify(content, signature, keyId)) {
            log.warn(
                "event=ad.ssv.invalid-signature mode={} userId={} keyId={} adUnit={}",
                policy.mode.configValue,
                userId,
                keyId,
                adUnit,
            )
            auditService.publish(
                userId = userId,
                action = "REWARD_AD_SSV_INVALID",
                resourceType = "Reward",
                resourceId = transactionId,
                payload =
                    mapOf(
                        "mode" to policy.mode.configValue,
                        "keyId" to keyId,
                        "adUnit" to (adUnit ?: ""),
                    ),
            )
            return ResponseEntity.status(403).build()
        }

        val outcome = nonceService.recordVerifiedSsv(userId, transactionId, customData)
        if (outcome.result == SsvNonceResult.DUPLICATE) {
            log.info(
                "event=ad.ssv.duplicate mode={} transactionId={} purpose={}",
                policy.mode.configValue,
                transactionId,
                outcome.purpose,
            )
            return ResponseEntity.ok().build()
        }

        val matched = outcome.result == SsvNonceResult.VERIFIED
        val action = if (outcome.result == SsvNonceResult.UNMATCHED) "REWARD_AD_SSV_UNMATCHED" else "REWARD_AD_SSV_VERIFIED"
        auditService.publish(
            userId = userId,
            action = action,
            resourceType = "Reward",
            resourceId = transactionId,
            payload =
                mapOf(
                    "mode" to policy.mode.configValue,
                    "result" to outcome.result.name,
                    "serverNonceMatched" to matched,
                    "purpose" to (outcome.purpose ?: ""),
                    "adUnit" to (adUnit ?: ""),
                    "adUnitAllowlistConfigured" to adUnitAllowlist.isNotEmpty(),
                    "rewardItem" to (request.getParameter("reward_item") ?: ""),
                ),
        )
        log.info(
            "event=ad.ssv.processed mode={} result={} userId={} adUnit={} purpose={}",
            policy.mode.configValue,
            outcome.result,
            userId,
            adUnit,
            outcome.purpose,
        )

        if (policy.mode == AdRewardMode.AUTHORITATIVE && !matched) {
            return ResponseEntity.status(403).build()
        }
        return ResponseEntity.ok().build()
    }

    /** signature 와 key_id 이외의 unsigned suffix 를 허용하지 않고 서명 대상 raw prefix 를 반환한다. */
    private fun signedContent(query: String?): String? {
        if (query.isNullOrBlank()) return null
        val marker = "&signature="
        val signatureIndex = query.indexOf(marker)
        if (signatureIndex <= 0 || query.indexOf(marker, signatureIndex + marker.length) >= 0) return null

        val unsignedParts = query.substring(signatureIndex + 1).split('&')
        if (
            unsignedParts.size != 2 ||
            !unsignedParts[0].startsWith("signature=") ||
            !unsignedParts[1].startsWith("key_id=")
        ) {
            return null
        }
        return query.substring(0, signatureIndex)
    }

    companion object {
        private const val MAX_SIGNATURE_LENGTH = 512
        private const val MAX_TRANSACTION_ID_LENGTH = 128
        private const val MAX_USER_ID_LENGTH = 64
    }
}
