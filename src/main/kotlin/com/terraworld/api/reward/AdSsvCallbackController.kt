package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import io.swagger.v3.oas.annotations.Hidden
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import kotlin.math.abs

/**
 * AdMob SSV(Server-Side Verification) callback. (fullstack-ultraplan WP-3, 2026-06-04 신설)
 *
 * Google AdMob 서버가 rewarded ad 시청 완료 시 본 endpoint 를 GET 호출(공개·무인증).
 * 본 컨트롤러는 (1) ECDSA 서명검증 (2) timestamp 신선도 (3) ad_unit allowlist(선택)
 * (4) transaction_id dedup 을 수행한다.
 *
 * ⚠️ 보상 미지급(이중지급 회피): 실 보상은 client `POST /rewards/ad`(nonce) 경로 1곳에서만.
 * 본 SSV 는 서버측 위조검증/감사 레이어 — 위조 SSV 는 403 차단, 정상 SSV 는 transaction_id 를
 * nonce inbox 에 기록(replay 차단)하고 감사 로그만 남긴다.
 * (향후 `reward.ad.nonce.required=true` 전환 후 SSV 를 authoritative grant 로 승격 가능 —
 * 그때 client 경로는 UX-only 로 전환.)
 *
 * @Hidden — OpenAPI spec 제외(외부 Google 호출). SecurityConfig 에서 본 경로만 permitAll
 * (`POST /rewards/ad` client 경로는 인증 유지).
 */
@RestController
@RequestMapping("/api/v1/rewards/ad")
@Hidden
class AdSsvCallbackController(
    private val verifier: AdSsvSignatureVerifier,
    private val nonceInboxRepository: AdRewardNonceInboxRepository,
    private val auditService: AuditService,
    @Value("\${admob.ssv.timestamp-tolerance-ms:300000}")
    private val timestampToleranceMs: Long,
    @Value("\${admob.ssv.ad-unit-allowlist:}")
    private val adUnitAllowlistCsv: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/ssv-callback")
    @Transactional
    fun handleSsv(request: HttpServletRequest): ResponseEntity<Void> {
        val query = request.queryString
        if (query.isNullOrBlank() || !query.contains("&signature=")) {
            log.warn("ad.ssv.malformed — signature param 없음")
            return ResponseEntity.badRequest().build()
        }
        // 검증 대상 = signature param 직전까지의 raw query string(Google 공식 spec).
        val content = query.substringBefore("&signature=")

        val signature = request.getParameter("signature")
        val keyId = request.getParameter("key_id")?.toLongOrNull()
        val transactionId = request.getParameter("transaction_id")
        val userId = request.getParameter("user_id")
        val timestamp = request.getParameter("timestamp")?.toLongOrNull()
        val adUnit = request.getParameter("ad_unit")

        if (signature.isNullOrBlank() || keyId == null || transactionId.isNullOrBlank() || userId.isNullOrBlank()) {
            log.warn("ad.ssv.missing-params")
            return ResponseEntity.badRequest().build()
        }

        // (2) timestamp 신선도(±tolerance, ms epoch). 제공 시 검증.
        if (timestamp != null) {
            val skew = abs(System.currentTimeMillis() - timestamp)
            if (skew > timestampToleranceMs) {
                log.warn("ad.ssv.stale-timestamp skewMs={}", skew)
                return ResponseEntity.status(403).build()
            }
        }

        // (3) ad_unit allowlist(설정 시에만)
        if (adUnitAllowlistCsv.isNotBlank() && adUnit != null) {
            val allow = adUnitAllowlistCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            if (adUnit !in allow) {
                log.warn("ad.ssv.ad-unit-not-allowed adUnit={}", adUnit)
                return ResponseEntity.status(403).build()
            }
        }

        // (1) ECDSA 서명검증
        if (!verifier.verify(content, signature, keyId)) {
            log.warn("ad.ssv.invalid-signature userId={} keyId={}", userId, keyId)
            auditService.publish(
                userId = userId,
                action = "REWARD_AD_SSV_INVALID",
                resourceType = "Reward",
                payload = mapOf("keyId" to keyId, "adUnit" to (adUnit ?: "")),
            )
            return ResponseEntity.status(403).build()
        }

        // (4) transaction_id dedup — SSV replay 차단.
        // Codex 보안 LOW: 64자(nonce inbox VARCHAR) 초과는 truncate(dedup 충돌 의미 변질) 대신 거부.
        if (transactionId.length > 64) {
            log.warn("ad.ssv.transaction-id-too-long len={}", transactionId.length)
            return ResponseEntity.status(400).build()
        }
        val inserted = nonceInboxRepository.insertIfAbsent(transactionId, userId, AdRewardNonceInbox.SOURCE_SSV_CALLBACK)
        if (inserted == 0) {
            log.info("ad.ssv.duplicate transactionId={}", transactionId)
            return ResponseEntity.ok().build()
        }

        auditService.publish(
            userId = userId,
            action = "REWARD_AD_SSV_VERIFIED",
            resourceType = "Reward",
            payload = mapOf("adUnit" to (adUnit ?: ""), "rewardItem" to (request.getParameter("reward_item") ?: "")),
        )
        log.info("ad.ssv.verified userId={} adUnit={}", userId, adUnit)
        return ResponseEntity.ok().build()
    }
}
