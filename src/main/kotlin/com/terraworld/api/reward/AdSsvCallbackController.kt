package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
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
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * AdMob Rewarded **Server-Side Verification (SSV)** 콜백 수신 엔드포인트.
 *
 * Google AdMob 서버가 보상형 광고 시청 완료 시 본 URL 로 **GET** 요청(query string)을 보낸다.
 * (공식 spec = GET + query params. 운영인계 문서의 "POST" 표기는 부정확 — GET 으로 구현.)
 *
 * 처리:
 *  1. raw query string 에서 `&signature=` 직전까지를 content 로 추출 → [AdSsvSignatureVerifier] 로
 *     ECDSA P-256 SHA-256 서명 검증 (위조 콜백 차단).
 *  2. transaction_id 를 `ad_reward_nonce_inbox` 에 dedup 기록 (Google 재시도/replay 차단).
 *  3. audit 발행.
 *
 * ⚠️ **보상 지급은 본 endpoint 에서 하지 않는다.** 현재 보상은 client 경로
 * (`claimAdReward`)가 nonce dedup 으로 지급한다. SSV 를 authoritative grant 로 승격하면
 * client 경로와 **이중지급** 위험 → 두 경로 통합은 별도 product 결정 필요(`reward.ad.nonce.required`
 * 전환과 함께). 본 cycle 은 문서화된 "signature 검증 layer" 역할까지만 구현.
 *
 * ⚠️ 인간/실트래픽 미검증 — 실제 AdMob 콜백 샘플로 검증(G2/G3 게이트) 전엔 '완료' 아님.
 *
 * @Hidden + SecurityConfig 의 permitAll(`/api/v1/rewards/ad/ssv-callback`) — Google 은 인증 없이 호출.
 *   실 인증 게이트는 본 컨트롤러의 서명 검증.
 */
@RestController
@RequestMapping("/api/v1/rewards/ad")
@Hidden
class AdSsvCallbackController(
    private val verifier: AdSsvSignatureVerifier,
    private val adRewardNonceInboxRepository: AdRewardNonceInboxRepository,
    private val auditService: AuditService,
    // 선택적 ad_unit allowlist (콤마 분리). 비어있으면 ad_unit 검사 skip.
    @Value("\${admob.ssv.allowed-ad-units:}")
    private val allowedAdUnits: List<String>,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AdSsvCallbackController::class.java)
        private const val SIGNATURE_MARKER = "&signature="
    }

    @GetMapping("/ssv-callback")
    @Transactional
    fun handleSsvCallback(request: HttpServletRequest): ResponseEntity<Void> {
        val rawQuery = request.queryString
        if (rawQuery.isNullOrBlank()) {
            return ResponseEntity.badRequest().build()
        }

        // 1) content = `&signature=` 직전까지 raw 부분문자열 (원본 그대로, 변형 금지).
        val sigMarkerIdx = rawQuery.indexOf(SIGNATURE_MARKER)
        if (sigMarkerIdx < 0) {
            log.warn("ad.ssv.callback — signature 파라미터 누락")
            return ResponseEntity.badRequest().build()
        }
        val content = rawQuery.substring(0, sigMarkerIdx)

        val params = parseQuery(rawQuery)
        val signature = params["signature"]
        val keyId = params["key_id"]?.toLongOrNull()
        val transactionId = params["transaction_id"]
        if (signature.isNullOrBlank() || keyId == null || transactionId.isNullOrBlank()) {
            log.warn("ad.ssv.callback — 필수 파라미터 누락 (signature/key_id/transaction_id)")
            return ResponseEntity.badRequest().build()
        }

        // 2) 서명 검증 — 위조 콜백 차단.
        if (!verifier.verify(content, signature, keyId)) {
            log.warn("ad.ssv.callback — 서명 검증 실패 keyId={} adUnit={}", keyId, params["ad_unit"])
            auditService.publish(
                userId = "anonymous",
                action = "REWARD_AD_SSV_INVALID_SIGNATURE",
                resourceType = "Reward",
                payload = mapOf("keyId" to keyId, "adUnit" to (params["ad_unit"] ?: "")),
            )
            return ResponseEntity.status(403).build()
        }

        // 3) ad_unit allowlist (설정 시).
        val adUnit = params["ad_unit"] ?: ""
        if (allowedAdUnits.isNotEmpty() && adUnit !in allowedAdUnits) {
            log.warn("ad.ssv.callback — 허용되지 않은 ad_unit={}", adUnit)
            auditService.publish(
                userId = "anonymous",
                action = "REWARD_AD_SSV_AD_UNIT_REJECTED",
                resourceType = "Reward",
                payload = mapOf("adUnit" to adUnit),
            )
            return ResponseEntity.status(403).build()
        }

        // 4) 사용자 식별 — user_id 파라미터 우선, 없으면 custom_data. (광고 요청 시 app 이 설정)
        val userId = params["user_id"]?.takeIf { it.isNotBlank() } ?: params["custom_data"]?.takeIf { it.isNotBlank() }
        if (userId == null) {
            // 서명은 유효하나 귀속할 사용자 없음 — 에러 아님(200), audit 만. Google 무한 재시도 회피.
            log.warn("ad.ssv.callback — user_id/custom_data 없음 (귀속 불가). txId={}", redact(transactionId))
            auditService.publish(
                userId = "anonymous",
                action = "REWARD_AD_SSV_UNATTRIBUTED",
                resourceType = "Reward",
                payload = mapOf("adUnit" to adUnit, "rewardItem" to (params["reward_item"] ?: "")),
            )
            return ResponseEntity.ok().build()
        }

        // 5) transaction_id dedup 기록 (SSV_CALLBACK). 중복(Google 재시도) 시 0 → idempotent 200.
        val nonceHash = sha256Hex(transactionId)
        val inserted = adRewardNonceInboxRepository.insertSsvIfAbsent(nonceHash, userId, transactionId)
        if (inserted == 0) {
            log.info("ad.ssv.callback — 중복 transaction (재시도) skip. txHash={}", nonceHash.take(12))
            return ResponseEntity.ok().build()
        }

        // 6) audit — 검증 성공한 SSV 신호 기록. (보상 지급은 본 endpoint 범위 밖 — 위 KDoc 참조)
        auditService.publish(
            userId = userId,
            action = "REWARD_AD_SSV_VERIFIED",
            resourceType = "Reward",
            payload =
                mapOf(
                    "adUnit" to adUnit,
                    "rewardAmount" to (params["reward_amount"] ?: ""),
                    "rewardItem" to (params["reward_item"] ?: ""),
                    "adNetwork" to (params["ad_network"] ?: ""),
                    "timestamp" to (params["timestamp"] ?: ""),
                ),
        )
        log.info("ad.ssv.callback — 검증·기록 완료 userId={} adUnit={}", userId, adUnit)
        return ResponseEntity.ok().build()
    }

    /** raw query string → url-decoded key/value map. (검증 content 는 raw 를 쓰고, 본 map 은 값 추출용) */
    private fun parseQuery(rawQuery: String): Map<String, String> =
        rawQuery
            .split("&")
            .mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx <= 0) {
                    null
                } else {
                    val key = pair.substring(0, idx)
                    val value = URLDecoder.decode(pair.substring(idx + 1), StandardCharsets.UTF_8)
                    key to value
                }
            }.toMap()

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun redact(value: String): String = if (value.length <= 8) "***" else value.take(4) + "..." + value.takeLast(4)
}
