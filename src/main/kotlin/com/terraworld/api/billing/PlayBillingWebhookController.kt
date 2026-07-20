package com.terraworld.api.billing

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.api.entitlement.EntitlementTxRefConflictException
import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import io.swagger.v3.oas.annotations.Hidden
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest
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
    // BE-01: inbox dedup + grant/revoke 를 하나의 짧은 tx 로 묶는 경계 (verify 는 tx 밖).
    private val billingWebhookTxService: BillingWebhookTxService,
    // BE-01: Discord alert @Async best-effort 분리 (요청/tx 스레드에서 동기 HTTP 금지).
    private val billingAlertService: BillingAlertService,
    @Value("\${terraworld.internal-api-token:}")
    private val expectedInternalToken: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private val GRANT_NOTIFICATION_TYPES =
            setOf(
                "ONE_TIME_PRODUCT_PURCHASED",
                "SUBSCRIPTION_PURCHASED",
                "SUBSCRIPTION_RECOVERED",
                "SUBSCRIPTION_RENEWED",
            )
        private val REVOKE_NOTIFICATION_TYPES =
            setOf(
                "REFUND",
                "ONE_TIME_PRODUCT_CANCELED",
                "SUBSCRIPTION_CANCELED",
                "SUBSCRIPTION_REVOKED",
                "SUBSCRIPTION_EXPIRED",
            )
    }

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
     * BE-01 (2026-07-15 성능 감사): 메서드 @Transactional 제거 + 처리 순서 재배치.
     *
     * 기존: tx { auth → inbox dedup → 매핑 → Google verify(외부 HTTP, timeout 8s) → grant/revoke }
     *  — verify 대기 동안 DB 커넥션(pool 10) 점유 (pool 고갈 벡터).
     * 신규: auth → 매핑 → verify(tx 밖) → tx { inbox dedup + grant/revoke }.
     *
     * 시맨틱 (N11 원자성 유지):
     *  - inbox insertIfAbsent 와 grant/revoke 는 [BillingWebhookTxService] 의 같은 짧은 tx 로
     *    원자 커밋 — grant 실패 시 inbox 도 rollback → 5xx → 재전송이 정상 재처리 (분리 금지).
     *  - 중복 notification 은 verify 를 재수행 후 dedup 에서 skip (dup 은 드묾 — 정합성 우선).
     *  - verify 실패(400/401/503)는 inbox 미기록 — 재전송이 dup-skip 으로 은폐되지 않고
     *    재시도된다 (pubsub 경로 SEC-002 의 fail-loud 철학과 정렬).
     */
    @PostMapping("/play-billing")
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

        val userId = body.userId
        val productId = body.productId
        val purchaseToken = body.purchaseToken
        val entitlementKey = mapProductIdToEntitlementKey(productId)

        if (entitlementKey == null) {
            // PAY-003 fix: unknown productId → warn log + Discord alert (운영 누락 회피).
            // BE-01: alert 는 @Async best-effort. 200 ACK = 재전송 없음 — inbox 기록 불요.
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
            billingAlertService.sendDiscordAlert("⚠️ Unknown Play product: `$productId` (type=${body.notificationType})")
            return ResponseEntity.ok().build()
        }

        // PAY-002 fix: Google Play API 로 purchaseToken cross-check.
        // BE-01: 외부 HTTP — tx 밖에서 수행 (DB 커넥션 미점유).
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
                billingAlertService.sendDiscordAlert("🚨 Play webhook user mismatch — productId=$productId")
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

        // 반영할 entitlement action 결정. 무처리 type 은 inbox 기록 없이 ACK (200 = 재전송 없음).
        val action: (() -> Unit)? =
            when (body.notificationType) {
                in GRANT_NOTIFICATION_TYPES -> {
                    {
                        // V36 재설계: tx_ref 충돌은 grant 가 자신의 tx 안에서 직접 throw —
                        // processWithInboxDedup 의 tx 에 참여 중이므로 inbox insert 까지 함께
                        // 롤백 + 500 전파 → 재전송이 duplicate-skip 으로 은폐되지 않는다.
                        // ALREADY_PRESENT / REVOKED(환불 토큰 terminal) 는 멱등 — 200 ACK.
                        entitlementService.grant(
                            userId = userId,
                            entitlementKey = entitlementKey,
                            reason = EntitlementEvent.REASON_PURCHASE,
                            txRef = purchaseToken,
                        )
                    }
                }
                in REVOKE_NOTIFICATION_TYPES -> {
                    {
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
                }
                else -> null
            }
        if (action == null) {
            log.info("billing.webhook.ignore type={}", body.notificationType)
            return ResponseEntity.ok().build()
        }

        // N11 fix (BE-01 재배치): webhook 중복 수신 dedup — inbox insertIfAbsent + grant/revoke 를
        // 같은 짧은 tx 로 원자 커밋 (Codex audit Q3 의 원자적 ON CONFLICT DO NOTHING 유지).
        // notificationId 미제공 (legacy caller) 시 dedup 우회 — entitlement grant 자체는
        // idempotent (uq_user_entitlement_tx_ref) 라 안전.
        val notificationId = body.notificationId
        if (notificationId == null) {
            log.warn("billing.webhook.no-notification-id — dedup 우회 (type={})", body.notificationType)
        }
        val processed =
            try {
                billingWebhookTxService.processWithInboxDedup(notificationId, body.notificationType, action)
            } catch (e: EntitlementTxRefConflictException) {
                // V36 재설계: 서비스 tx 안 throw → inbox insert 까지 함께 롤백된 뒤 여기 도달.
                // conflict audit 은 롤백과 함께 유실되므로 tx 밖(여기)서 publish 후 재-throw →
                // 500 → 재전송이 duplicate-skip 으로 은폐되지 않는다.
                publishTxRefConflictAudit(e)
                throw e
            }
        if (!processed) {
            log.info(
                "billing.webhook.duplicate-skip notificationId={} type={}",
                notificationId,
                body.notificationType,
            )
        }
        return ResponseEntity.ok().build()
    }

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
     *
     * BE-01 (2026-07-15 성능 감사): 메서드 @Transactional 제거. 외부 HTTP(Play API
     * resolveObfuscatedAccountId / Discord alert)는 tx 밖 — DB 반영(messageId dedup +
     * grant/revoke)만 [BillingWebhookTxService] 의 같은 짧은 tx 로 원자 커밋. malformed/
     * missing-field 류는 200 ACK 자체가 Pub/Sub 재전송을 차단하므로 inbox 기록 불요
     * (기존 SEC-001 의 "throw 금지 → ACK" 시맨틱 유지 — dedup 선커밋은 tx 축소로 불필요해짐).
     */
    @PostMapping("/play-billing/pubsub")
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
        // SEC-001 (Codex MEDIUM) — BE-01 변형: malformed base64/JSON 은 200 OK 로 ACK.
        // 200 ACK 자체가 Pub/Sub 재전송을 차단하므로 (기존의 dedup 선커밋 없이도)
        // redelivery storm 이 발생하지 않는다. decode 는 tx 밖 — throw/rollback 상호작용 없음.
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
            // 필드 누락 = 처리 불능. 200 ACK 로 재전송 차단 (inbox 기록 불요 — BE-01).
            log.warn("billing.pubsub.missing-fields messageId={}", messageId)
            return ResponseEntity.ok().build()
        }
        val entitlementKey = mapProductIdToEntitlementKey(sku)
        if (entitlementKey == null) {
            billingAlertService.sendDiscordAlert("⚠️ Unknown Play product (pubsub): `$sku`")
            return ResponseEntity.ok().build()
        }
        // userId 해석 — verifier enabled 확정 상태. BE-01: 외부 HTTP(Play API) — tx 밖에서 수행.
        // "확정 부재"만 ACK 하고, 일시 실패(Unavailable)는 503 으로 Pub/Sub 재전송을 유도한다 —
        // 구현이 null 로 뭉개던 시절엔 장애 중 도착한 구매/취소 알림이 영구 유실됐다 (audit B1-4).
        val userId =
            when (val resolution = verifier.resolveObfuscatedAccountId(sku, purchaseToken)) {
                is PlayPurchaseVerifier.AccountResolution.Resolved -> resolution.userId
                is PlayPurchaseVerifier.AccountResolution.Absent -> {
                    log.warn("billing.pubsub.no-account sku={} (obfuscatedAccountId 확정 부재)", sku)
                    return ResponseEntity.ok().build()
                }
                is PlayPurchaseVerifier.AccountResolution.Unavailable -> {
                    log.warn("billing.pubsub.resolve-unavailable sku={} — 503 재전송 유도", sku)
                    return ResponseEntity.status(503).build()
                }
            }
        val action: (() -> Unit)? =
            when (notifType) {
                1 -> {
                    // V36 재설계: tx_ref 충돌은 grant 가 자신의 tx 안에서 직접 throw (아래 catch 참조).
                    { entitlementService.grant(userId, entitlementKey, EntitlementEvent.REASON_PURCHASE, txRef = purchaseToken) }
                }
                2 -> {
                    { entitlementService.revoke(userId, entitlementKey, EntitlementEvent.REASON_CHARGEBACK, txRef = purchaseToken) }
                }
                else -> null
            }
        if (action == null) {
            log.info("billing.pubsub.ignore-type type={}", notifType)
            return ResponseEntity.ok().build()
        }
        // BE-01 불변 제약: messageId dedup insert + grant/revoke 는 같은 짧은 tx 로 커밋.
        // inbox 만 먼저 커밋하면 grant 실패 후 재전송이 duplicate 판정 → 권리 영구 유실.
        val processed =
            try {
                billingWebhookTxService.processWithInboxDedup(messageId, "pubsub", action)
            } catch (e: EntitlementTxRefConflictException) {
                // V36 재설계: tx 롤백(inbox 포함) 후 tx 밖에서 conflict audit publish + 재-throw.
                publishTxRefConflictAudit(e)
                throw e
            }
        if (!processed) {
            log.info("billing.pubsub.duplicate-skip messageId={}", messageId)
        }
        return ResponseEntity.ok().build()
    }

    // 매핑 단일 SoT = ProductEntitlementMapper (IapVerifyController 와 공유 — 2026-06-04 dedupe).
    private fun mapProductIdToEntitlementKey(productId: String): String? = ProductEntitlementMapper.toEntitlementKey(productId)

    /**
     * V36 재설계: tx_ref 충돌 audit — grant 가 서비스 tx 안에서 throw 하므로 tx 안 publish 는
     * 롤백과 함께 유실된다 (AFTER_COMMIT 미도달, fallbackExecution 은 tx 부재 시에만 동작).
     * 컨트롤러(tx 밖)에서 예외 필드로 기록해 audit 유실을 막는다.
     */
    private fun publishTxRefConflictAudit(e: EntitlementTxRefConflictException) {
        auditService.publish(
            userId = e.userId,
            action = "ENTITLEMENT_GRANT_TXREF_CONFLICT",
            resourceType = "Entitlement",
            resourceId = e.entitlementKey,
            payload = mapOf("reason" to e.reason, "txRef" to (e.txRef ?: "null")),
        )
    }

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
