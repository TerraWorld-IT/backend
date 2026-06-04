package com.terraworld.api.billing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.audit.AuditService
import com.terraworld.domain.billing.WebhookInboxRepository
import com.terraworld.domain.entitlement.EntitlementEvent
import io.swagger.v3.oas.annotations.Hidden
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.Base64

/**
 * UltraPlan v3 J-FREE-001 + Codex audit (2026-05-18):
 *
 * Google Play Real-time Developer Notification (RTDN) webhook 수신.
 *
 * RTDN type 처리:
 *  - SUBSCRIPTION_RECOVERED / SUBSCRIPTION_RENEWED / ONE_TIME_PRODUCT_PURCHASED → grant
 *  - SUBSCRIPTION_CANCELED / SUBSCRIPTION_REVOKED / ONE_TIME_PRODUCT_CANCELED → revoke
 *  - REFUND (모든 product type) → revoke
 *
 * 보안 (Codex audit HIGH 2 + 3 fix):
 *  - PAY-001: prod profile + token 빈 문자열 시 boot fail (active=prod && token.isBlank() → IllegalStateException)
 *  - PAY-001: constant-time 비교 (MessageDigest.isEqual) — timing attack 방어
 *  - PAY-002: PlayPurchaseVerifier.verifyToken 로 Google API cross-check (별 cycle 활성화)
 *  - PAY-003: unknown productId → Discord webhook alert (운영 누락 회피)
 *
 * @Hidden — OpenAPI spec 에서 제외 (internal webhook).
 */
@RestController
@RequestMapping("/api/v1/webhooks")
@Hidden
class PlayBillingWebhookController(
    private val entitlementService: EntitlementService,
    private val verifier: PlayPurchaseVerifier,
    private val auditService: AuditService,
    private val objectMapper: ObjectMapper,
    private val environment: Environment,
    // N11 (구현 계획서 v4): webhook 중복 수신 dedup inbox
    private val webhookInboxRepository: WebhookInboxRepository,
    @Value("\${terraworld.internal-api-token:}")
    private val expectedInternalToken: String,
    @Value("\${terraworld.billing.alert-discord-webhook:}")
    private val alertDiscordWebhook: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /**
     * PAY-001 fix: prod profile 부팅 시 internal-api-token 미설정 = fail-fast.
     * 빈 문자열 fallback 으로 webhook 무력화 위험 차단.
     */
    @PostConstruct
    fun validateConfigOrFail() {
        val activeProfiles = environment.activeProfiles
        val isProd = activeProfiles.any { it.equals("prod", ignoreCase = true) }
        if (isProd && expectedInternalToken.isBlank()) {
            throw IllegalStateException(
                "PAY-001 (Codex audit HIGH): prod 프로필 + terraworld.internal-api-token 빈 문자열 — " +
                    "webhook 인증 무력화 위험. application-prod.yml 에 secret 주입 필수.",
            )
        }
        if (expectedInternalToken.isBlank()) {
            log.warn(
                "billing.webhook.config — internal-api-token 빈 문자열 (non-prod). " +
                    "본 환경에서는 webhook 모든 호출 401 반환. 개발 시 token 주입 또는 mock RTDN 사용 권장.",
            )
        }
    }

    /**
     * N11 (구현 계획서 v4): inbox dedup + 처리를 단일 @Transactional 로 묶음.
     * inbox row 저장과 entitlement grant/revoke 가 atomic — 처리 실패 시 inbox 도 rollback
     * 되어 재시도 시 정상 재처리.
     */
    @PostMapping("/play-billing")
    @Transactional
    fun handleRtdn(
        @RequestHeader("X-Internal-Token", required = false) token: String?,
        @RequestBody body: PlayBillingNotification,
    ): ResponseEntity<Void> {
        // PAY-001 fix: constant-time 비교 (timing attack 방어).
        // 빈 expectedToken 인 경우 (non-prod, validateConfigOrFail 이 warn 후 부팅 허용) 모두 401.
        if (expectedInternalToken.isBlank() || token.isNullOrBlank() || !constantTimeEquals(token, expectedInternalToken)) {
            log.warn(
                "billing.webhook.unauthorized — token mismatch (productId={}, notificationType={})",
                body.productId,
                body.notificationType,
            )
            // code-review SEC-004 (2026-05-21): unauthorized 분기는 미인증 요청이므로
            // user-controlled body.userId 를 audit subject 로 쓰면 audit 오염 (타 사용자 누명).
            // subject 는 "anonymous" 고정, 주장된 userId 는 payload 의 claimedUserId 로만 기록.
            auditService.publish(
                userId = "anonymous",
                action = "BILLING_WEBHOOK_UNAUTHORIZED",
                resourceType = "Billing",
                payload =
                    mapOf(
                        "claimedUserId" to body.userId.ifBlank { "(blank)" },
                        "productId" to body.productId,
                        "notificationType" to body.notificationType,
                    ),
            )
            return ResponseEntity.status(401).build()
        }

        // N11 fix: webhook 중복 수신 dedup. notificationId 가 이미 inbox 에 있으면 재처리 skip.
        // Codex audit Q3: check-then-insert race 회피 — 원자적 INSERT ... ON CONFLICT DO NOTHING.
        // inserted == 0 (conflict) 이면 이미 처리된 notification → skip.
        // notificationId 미제공 (legacy caller) 시 dedup 우회 — entitlement grant 자체는
        // idempotent (uq_user_entitlement_tx_ref) 라 안전.
        val notificationId = body.notificationId
        if (notificationId != null) {
            val inserted = webhookInboxRepository.insertIfAbsent(notificationId, body.notificationType)
            if (inserted == 0) {
                log.info(
                    "billing.webhook.duplicate-skip notificationId={} type={}",
                    notificationId,
                    body.notificationType,
                )
                return ResponseEntity.ok().build()
            }
        } else {
            log.warn("billing.webhook.no-notification-id — dedup 우회 (type={})", body.notificationType)
        }

        val userId = body.userId
        val productId = body.productId
        val purchaseToken = body.purchaseToken
        val entitlementKey = mapProductIdToEntitlementKey(productId)

        if (entitlementKey == null) {
            // PAY-003 fix: unknown productId → warn log + Discord alert (운영 누락 회피)
            log.warn("billing.webhook.unknown-product productId={} purchaseToken={}", productId, redact(purchaseToken))
            auditService.publish(
                userId = userId.ifBlank { "anonymous" },
                action = "BILLING_WEBHOOK_UNKNOWN_PRODUCT",
                resourceType = "Billing",
                payload =
                    mapOf(
                        "productId" to productId,
                        "notificationType" to body.notificationType,
                    ),
            )
            sendDiscordAlert("⚠️ Unknown Play product: `$productId` (type=${body.notificationType})")
            return ResponseEntity.ok().build()
        }

        // PAY-002 fix: Google Play API 로 purchaseToken cross-check.
        // 별 cycle 활성화 (serviceAccountJsonPath 주입) 전까지는 Disabled 반환 — 본 cycle 운영 시
        // **개발/스테이징 환경 only** 로 사용 (prod 는 verifier 활성화 필수).
        val verification = verifier.verifyToken(productId, purchaseToken, userId)
        when (verification) {
            is VerificationResult.UserMismatch -> {
                log.warn(
                    "billing.webhook.user-mismatch expected={} actual={} productId={}",
                    verification.expected,
                    verification.actual,
                    productId,
                )
                auditService.publish(
                    userId = userId,
                    action = "BILLING_WEBHOOK_USER_MISMATCH",
                    resourceType = "Billing",
                    payload =
                        mapOf(
                            "expected" to verification.expected,
                            "actual" to verification.actual,
                            "productId" to productId,
                        ),
                )
                sendDiscordAlert("🚨 Play webhook user mismatch — productId=$productId")
                return ResponseEntity.status(401).build()
            }
            is VerificationResult.InvalidToken -> {
                log.warn("billing.webhook.invalid-token reason={} productId={}", verification.reason, productId)
                return ResponseEntity.status(400).build()
            }
            VerificationResult.Disabled -> {
                // Codex 보안 HIGH: prod 에서 verifier 미설정(service-account 부재) = Google 검증 없이
                // internal-token-authed body 만으로 entitlement 변경 가능 → 거부(503).
                // non-prod 는 dev/test mock 경로로 진행 허용.
                val isProd = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
                if (isProd) {
                    log.error("billing.webhook.verify-disabled-in-prod productId={} — service-account 주입 필수", productId)
                    return ResponseEntity.status(503).build()
                }
                log.info("billing.webhook.verify-disabled productId={} (dev/test 경로)", productId)
            }
            VerificationResult.Ok -> {
                log.info("billing.webhook.verify-ok productId={}", productId)
            }
        }

        when (body.notificationType) {
            "ONE_TIME_PRODUCT_PURCHASED",
            "SUBSCRIPTION_PURCHASED",
            "SUBSCRIPTION_RECOVERED",
            "SUBSCRIPTION_RENEWED",
            -> {
                entitlementService.grant(
                    userId = userId,
                    entitlementKey = entitlementKey,
                    reason = EntitlementEvent.REASON_PURCHASE,
                    txRef = purchaseToken,
                )
            }
            "REFUND",
            "ONE_TIME_PRODUCT_CANCELED",
            "SUBSCRIPTION_CANCELED",
            "SUBSCRIPTION_REVOKED",
            "SUBSCRIPTION_EXPIRED",
            -> {
                entitlementService.revoke(
                    userId = userId,
                    entitlementKey = entitlementKey,
                    reason =
                        when (body.notificationType) {
                            "REFUND" -> EntitlementEvent.REASON_REFUND
                            else -> EntitlementEvent.REASON_CHARGEBACK
                        },
                    txRef = purchaseToken,
                )
            }
            else -> {
                log.info("billing.webhook.ignore type={}", body.notificationType)
            }
        }

        return ResponseEntity.ok().build()
    }

    /**
     * Google Play product ID → entitlement key 매핑.
     *
     * UltraPlan v3 §3.A 의 권장 default (§4.2.A 자율 진행) 가격:
     *  - free_placement_unlock (₩1,900, 1회 영구) → free_placement
     *  - season_pass_2026q4 (₩3,900/월) → season_pass_2026q4
     *  - limited_figure_* (₩2,900/팩) → limited_figure_*
     *  - memo_theme_pack_* (₩1,900/팩) → theme_pack_*
     */
    /**
     * Google Play RTDN — 실 Pub/Sub push 포맷 ingestion. (fullstack-ultraplan WP-1-F, 2026-06-04)
     *
     * Pub/Sub push envelope: { message: { data: <base64 JSON>, messageId }, subscription }.
     * data 디코드 = { packageName, eventTimeMillis, oneTimeProductNotification: { notificationType, purchaseToken, sku } }.
     * userId 는 body 에 없음 → purchaseToken 으로 Play API 의 obfuscatedExternalAccountId(=userId) 조회.
     *
     * 인증: Pub/Sub push subscription URL 의 `?token=<shared secret>`(INTERNAL_API_TOKEN), constant-time.
     * one-time notificationType: 1=PURCHASED→grant, 2=CANCELED→revoke. messageId dedup.
     * ⚠️ 실 RTDN 트래픽 미검증(.env-ready) — Pub/Sub 구독 + service-account 후 1회 검증 필요.
     */
    @PostMapping("/play-billing/pubsub")
    @Transactional
    fun handlePubSubRtdn(
        @RequestParam(name = "token", required = false) token: String?,
        @RequestBody envelope: PubSubEnvelope,
    ): ResponseEntity<Void> {
        if (expectedInternalToken.isBlank() || token.isNullOrBlank() || !constantTimeEquals(token, expectedInternalToken)) {
            log.warn("billing.pubsub.unauthorized")
            return ResponseEntity.status(401).build()
        }
        // SEC-002 (Codex MEDIUM): verifier 미설정(service-account 부재) 시 purchaseToken→userId resolve 불가.
        // prod 에서 조용히 200 ACK-drop 하면 실 구매/취소 알림이 유실 → fail-loud. dedup insert *이전* 에
        // 검사해 503 반환 → @Transactional 롤백 없이도 dedup 미기록 → Pub/Sub 가 재전송/dead-letter 로
        // misconfig 를 surface (dedup 이후 503 이면 다음 재전송이 dup-skip 돼 영구 은폐됨).
        if (!verifier.isEnabled()) {
            val isProd = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
            if (isProd) {
                log.error("billing.pubsub.verifier-disabled-in-prod — service-account 주입 필수 (Pub/Sub 재전송 유도)")
                return ResponseEntity.status(503).build()
            }
            log.info("billing.pubsub.verifier-disabled (dev no-op)")
            return ResponseEntity.ok().build()
        }

        val dataB64 = envelope.message?.data
        if (dataB64.isNullOrBlank()) {
            return ResponseEntity.ok().build()
        }
        val messageId = envelope.message.messageId
        if (messageId != null && webhookInboxRepository.insertIfAbsent(messageId, "pubsub") == 0) {
            log.info("billing.pubsub.duplicate-skip messageId={}", messageId)
            return ResponseEntity.ok().build()
        }
        // SEC-001 (Codex MEDIUM): malformed base64/JSON 은 200 OK 로 ACK (messageId 는 위에서 insert 완료).
        // throw 시 @Transactional 롤백 → dedup insert 취소 → 동일 메시지 무한 redelivery storm. 차단.
        val root =
            try {
                objectMapper.readTree(String(Base64.getDecoder().decode(dataB64), Charsets.UTF_8))
            } catch (e: IllegalArgumentException) {
                log.warn("billing.pubsub.malformed-base64 messageId={} msg={}", messageId, e.message)
                return ResponseEntity.ok().build()
            } catch (e: JsonProcessingException) {
                log.warn("billing.pubsub.malformed-json messageId={} msg={}", messageId, e.message)
                return ResponseEntity.ok().build()
            }
        val otp = root.get("oneTimeProductNotification")
        if (otp == null || otp.isNull) {
            log.info("billing.pubsub.ignore — non one-time notification")
            return ResponseEntity.ok().build()
        }
        val notifType = otp.get("notificationType")?.asInt() ?: -1
        val purchaseToken = otp.get("purchaseToken")?.asText()
        val sku = otp.get("sku")?.asText()
        if (purchaseToken.isNullOrBlank() || sku.isNullOrBlank()) {
            // 필드 누락 = 처리 불능. dedup 은 이미 커밋(위 insert) → ACK 로 재전송 차단.
            log.warn("billing.pubsub.missing-fields messageId={}", messageId)
            return ResponseEntity.ok().build()
        }
        val entitlementKey = mapProductIdToEntitlementKey(sku)
        if (entitlementKey == null) {
            sendDiscordAlert("⚠️ Unknown Play product (pubsub): `$sku`")
            return ResponseEntity.ok().build()
        }
        // userId 해석 — verifier enabled 확정 상태. 부재 = genuine not-found (client setObfuscatedAccountId 누락 추정).
        val userId = verifier.resolveObfuscatedAccountId(sku, purchaseToken)
        if (userId.isNullOrBlank()) {
            log.warn("billing.pubsub.no-account sku={} (obfuscatedAccountId 부재)", sku)
            return ResponseEntity.ok().build()
        }
        when (notifType) {
            1 -> entitlementService.grant(userId, entitlementKey, EntitlementEvent.REASON_PURCHASE, txRef = purchaseToken)
            2 -> entitlementService.revoke(userId, entitlementKey, EntitlementEvent.REASON_CHARGEBACK, txRef = purchaseToken)
            else -> log.info("billing.pubsub.ignore-type type={}", notifType)
        }
        return ResponseEntity.ok().build()
    }

    // 매핑 단일 SoT = ProductEntitlementMapper (IapVerifyController 와 공유 — 2026-06-04 dedupe).
    private fun mapProductIdToEntitlementKey(productId: String): String? = ProductEntitlementMapper.toEntitlementKey(productId)

    /** PAY-001 fix: timing attack 방어. SHA-256 후 MessageDigest.isEqual (constant-time). */
    private fun constantTimeEquals(
        provided: String,
        expected: String,
    ): Boolean {
        val md = MessageDigest.getInstance("SHA-256")
        val pHash = md.digest(provided.toByteArray(Charsets.UTF_8))
        val eHash = md.digest(expected.toByteArray(Charsets.UTF_8))
        return MessageDigest.isEqual(pHash, eHash)
    }

    /** purchaseToken 일부만 로그 노출 (민감 정보). */
    private fun redact(token: String): String {
        if (token.length <= 8) return "***"
        return token.take(4) + "..." + token.takeLast(4)
    }

    /** Discord webhook alert (실구현, 2026-06-04 WP-1). alert 실패는 main flow 비차단(try/catch). */
    private fun sendDiscordAlert(message: String) {
        if (alertDiscordWebhook.isBlank()) {
            log.info("billing.alert.discord-skip — webhook URL 미설정. message: {}", message)
            return
        }
        try {
            // Codex MED: 수동 escape 대신 Jackson 직렬화 — \r \t 등 제어문자/injection 안전.
            val body = objectMapper.writeValueAsString(mapOf("content" to message))
            val req =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(alertDiscordWebhook))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            httpClient.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            log.warn("billing.alert.discord-failed msg={}", e.message)
        }
    }
}

data class PlayBillingNotification(
    val userId: String,
    val notificationType: String,
    val productId: String,
    val purchaseToken: String,
    // N11 (구현 계획서 v4): webhook dedup 키 (Pub/Sub messageId 등). 미제공 시 dedup 우회.
    val notificationId: String? = null,
)

/** Google Pub/Sub push envelope (RTDN 실 포맷). (WP-1-F, 2026-06-04) */
data class PubSubEnvelope(
    val message: PubSubMessage? = null,
    val subscription: String? = null,
)

data class PubSubMessage(
    val data: String? = null,
    val messageId: String? = null,
)
