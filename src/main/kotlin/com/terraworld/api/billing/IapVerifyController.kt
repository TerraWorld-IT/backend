package com.terraworld.api.billing

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Hidden
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * IAP(인앱결제) 구매 검증 endpoint. (fullstack-ultraplan WP-1, 2026-06-04 신설)
 *
 * client(usePayment.startPurchase) 가 Play Billing 구매 완료 후 purchaseToken 을 본 endpoint 로
 * 전달 → 서버가 검증 후 entitlement 부여:
 *  - **test-mode** (`BILLING_TEST_MODE=true`, 기본): Google API 검증 skip → 정적 SKU/디버그로
 *    즉시 grant. 빌드 즉시 client→server→entitlement→UI 전 흐름 테스트 가능.
 *  - **live** (false): [PlayPurchaseVerifier] 로 Google Play Developer API cross-check. Ok 시 grant.
 *
 * RTDN webhook([PlayBillingWebhookController]) 과 별개의 client-driven 검증 — RTDN 은 환불/취소 backstop.
 * 멱등: `EntitlementService.grant` 의 unique(uq_user_entitlement_tx_ref) 로 중복 차단.
 *
 * 인증: SecurityConfig 의 `.anyRequest().authenticated()` 적용(permitAll 목록 외) → 로그인 필수.
 * @Hidden — 기존 off-spec 내부 endpoint 패턴(useInternalApi 호환).
 */
@RestController
@RequestMapping("/api/v1/billing/iap")
@Hidden
class IapVerifyController(
    private val verifier: PlayPurchaseVerifier,
    private val entitlementService: EntitlementService,
    private val auditService: AuditService,
    private val environment: Environment,
    // Codex 보안 HIGH-1 (2026-06-04): 기본값 false. test-mode 는 dev 에서 BILLING_TEST_MODE=true
    // 로 명시 활성화해야만 동작 — prod 프로필 누락/오설정 시에도 무료 grant 사고 차단(미설정=live 경로).
    @Value("\${terraworld.billing.test-mode:false}")
    private val testMode: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/verify")
    @Transactional
    fun verify(
        @RequestBody body: IapVerifyRequest,
    ): ResponseEntity<IapVerifyResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val entitlementKey =
            ProductEntitlementMapper.toEntitlementKey(body.productId)
                ?: throw BusinessException(ErrorCode.INVALID_INPUT)

        if (body.purchaseToken.isBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }

        // prod 안전장치: prod + test-mode=true = 무료 entitlement 사고 → 거부.
        // (application-prod.yml 에서 BILLING_TEST_MODE=false 강제 + 본 방어 이중화.)
        val isProd = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }
        if (isProd && testMode) {
            log.error("billing.iap.prod-test-mode — prod 에서 BILLING_TEST_MODE=true 금지(무료지급 사고)")
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }

        if (testMode) {
            // Codex 보안 HIGH-2: test-mode 는 Google 검증을 건너뛰므로 임의 productId prefix 로
            // 상위 entitlement 획득 가능. HIGH-1 fix(test-mode=dev 전용·prod 차단)로 dev 환경에
            // bound 됨 — dev/QA 의 의도된 미검증 grant. live(prod) 는 verifier 가 token 검증.
            log.info("billing.iap.test-mode-grant userId={} product={}", userId, body.productId)
        } else {
            when (val v = verifier.verifyToken(body.productId, body.purchaseToken, userId)) {
                VerificationResult.Ok ->
                    log.info("billing.iap.verify-ok userId={} product={}", userId, body.productId)
                VerificationResult.Disabled -> {
                    // live-mode 인데 verifier 미설정(service-account 부재) = config 오류. grant 금지.
                    log.error("billing.iap.verifier-disabled-in-live userId={} product={}", userId, body.productId)
                    throw BusinessException(ErrorCode.INVALID_INPUT)
                }
                is VerificationResult.UserMismatch -> {
                    auditService.publish(
                        userId = userId,
                        action = "BILLING_IAP_USER_MISMATCH",
                        resourceType = "Billing",
                        payload = mapOf("expected" to v.expected, "actual" to v.actual, "product" to body.productId),
                    )
                    return ResponseEntity.status(401).build()
                }
                is VerificationResult.InvalidToken -> {
                    log.warn("billing.iap.invalid-token reason={} product={}", v.reason, body.productId)
                    return ResponseEntity.status(400).build()
                }
            }
        }

        val granted =
            entitlementService.grant(
                userId = userId,
                entitlementKey = entitlementKey,
                reason = EntitlementEvent.REASON_PURCHASE,
                txRef = body.purchaseToken,
            )
        auditService.publish(
            userId = userId,
            action = "BILLING_IAP_VERIFIED",
            resourceType = "Billing",
            payload =
                mapOf(
                    "product" to body.productId,
                    "entitlementKey" to entitlementKey,
                    "granted" to granted,
                    "testMode" to testMode,
                ),
        )
        return ResponseEntity.ok(IapVerifyResponse(granted = granted, entitlementKey = entitlementKey))
    }
}

data class IapVerifyRequest(
    val productId: String,
    val purchaseToken: String,
    val platform: String = "ANDROID",
)

data class IapVerifyResponse(
    val granted: Boolean,
    val entitlementKey: String,
)
