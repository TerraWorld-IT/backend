package com.terraworld.api.billing

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.api.entitlement.EntitlementTxRefConflictException
import com.terraworld.api.entitlement.GrantResult
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
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * IAP(인앱결제) 구매 검증 endpoint. (fullstack-ultraplan WP-1, 2026-06-04 신설 · 2026-06-23 iOS App Store 지원)
 *
 * client(usePayment.startPurchase) 가 구매 완료 후 토큰을 본 endpoint 로 전달 → 서버가 platform 별
 * verifier(ANDROID→[PlayPurchaseVerifier], IOS→[AppStorePurchaseVerifier])로 검증 후 entitlement 부여:
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
    private val appStoreVerifier: AppStorePurchaseVerifier,
    private val entitlementService: EntitlementService,
    private val auditService: AuditService,
    private val environment: Environment,
    // Codex 보안 HIGH-1 (2026-06-04): 기본값 false. test-mode 는 dev 에서 BILLING_TEST_MODE=true
    // 로 명시 활성화해야만 동작 — prod 프로필 누락/오설정 시에도 무료 grant 사고 차단(미설정=live 경로).
    @Value("\${terraworld.billing.test-mode:false}")
    private val testMode: Boolean,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * BE-01 (2026-07-15 성능 감사): 메서드 레벨 @Transactional 제거.
     *
     * 기존: 컨트롤러 tx 안에서 Google/Apple verify(외부 HTTP, timeout 8s) 호출 — DB 커넥션
     * (pool 10)을 외부 응답 대기 동안 점유하는 pool 고갈 벡터. 변경 후 tx 경계:
     *  - verify(외부 HTTP): tx 밖 — DB 커넥션 미점유.
     *  - grant + GRANT ledger: [EntitlementService.grant] 자체의 @Transactional (짧은 tx,
     *    별도 bean 이라 proxy 적용 — self-invocation 아님). 멱등 키 = tx_ref(purchaseToken,
     *    uq_user_entitlement_tx_ref) + PK(user_id, entitlement_key).
     *  - verify 성공 + grant 실패(예외): 그대로 전파 → 5xx — 클라이언트(usePayment) 재시도
     *    경로 유지. 200 으로 삼키면 스토어 결제 완료 + entitlement 미부여가 영구 은폐된다.
     *  - audit publish: tx 밖 발행 — AuditEventListener 의 fallbackExecution=true 로 수신.
     */
    @PostMapping("/verify")
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
        // txRef 로 저장되는 purchaseToken 은 user_entitlement.tx_ref VARCHAR(128) — 초과 시
        // silent truncation/멱등 키 충돌 방지(리뷰 IAP-003).
        if (body.purchaseToken.length > 128) {
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
            // Codex 보안 HIGH-2: test-mode 는 store 검증을 건너뛰므로 임의 productId prefix 로
            // 상위 entitlement 획득 가능. HIGH-1 fix(test-mode=dev 전용·prod 차단)로 dev 환경에
            // bound 됨 — dev/QA 의 의도된 미검증 grant. live(prod) 는 verifier 가 token 검증.
            log.info("billing.iap.test-mode-grant userId={} product={} platform={}", userId, body.productId, body.platform)
        } else {
            // platform 별 verifier 라우팅. 알 수 없는 platform 은 silent 미스라우팅 금지(명시 거부).
            val result =
                when (body.platform.uppercase()) {
                    "ANDROID", "GOOGLE_PLAY" ->
                        verifier.verifyToken(body.productId, body.purchaseToken, userId)
                    "IOS", "APP_STORE" -> {
                        // iOS: purchaseToken = StoreKit transactionId(txRef), receipt = base64 app receipt(필수).
                        val receipt = body.receipt
                        if (receipt.isNullOrBlank()) {
                            log.warn("billing.iap.ios-missing-receipt userId={} product={}", userId, body.productId)
                            throw BusinessException(ErrorCode.INVALID_INPUT)
                        }
                        appStoreVerifier.verifyToken(body.productId, body.purchaseToken, receipt)
                    }
                    else -> {
                        log.warn("billing.iap.unknown-platform userId={} platform={}", userId, body.platform)
                        throw BusinessException(ErrorCode.INVALID_INPUT)
                    }
                }
            when (result) {
                VerificationResult.Ok ->
                    log.info("billing.iap.verify-ok userId={} product={} platform={}", userId, body.productId, body.platform)
                VerificationResult.Disabled -> {
                    // live-mode 인데 verifier 미설정(키 부재) = config 오류. grant 금지(fail-closed).
                    log.error("billing.iap.verifier-disabled-in-live userId={} product={} platform={}", userId, body.productId, body.platform)
                    throw BusinessException(ErrorCode.INVALID_INPUT)
                }
                is VerificationResult.UserMismatch -> {
                    auditService.publish(
                        userId = userId,
                        action = "BILLING_IAP_USER_MISMATCH",
                        resourceType = "Billing",
                        payload = mapOf("expected" to result.expected, "actual" to result.actual, "product" to body.productId),
                    )
                    return ResponseEntity.status(401).build()
                }
                is VerificationResult.InvalidToken -> {
                    log.warn("billing.iap.invalid-token reason={} product={} platform={}", result.reason, body.productId, body.platform)
                    return ResponseEntity.status(400).build()
                }
            }
        }

        // V36 재설계: tx_ref 충돌은 [EntitlementService.grant] 가 자신의 @Transactional 안에서
        // 직접 throw (entitlement/원장 함께 롤백). 200/granted=false 로 삼키면 스토어 결제 완료 +
        // entitlement 미부여가 영구 은폐된다 — 5xx 전파 → 클라(usePayment) 재시도 + 운영 surface.
        // conflict audit 은 서비스 tx 롤백과 함께 유실되므로 여기(tx 밖)서 publish —
        // AuditEventListener 의 fallbackExecution=true 가 tx 부재 publish 를 수신한다.
        val grantResult =
            try {
                entitlementService.grant(
                    userId = userId,
                    entitlementKey = entitlementKey,
                    reason = EntitlementEvent.REASON_PURCHASE,
                    txRef = body.purchaseToken,
                )
            } catch (e: EntitlementTxRefConflictException) {
                publishTxRefConflictAudit(e)
                throw e
            }
        // ALREADY_PRESENT(같은 토큰의 중복 verify) 는 멱등 성공 — granted=false 로만 주면 클라가
        // "이미 소유"와 "검증 실패"를 구분 못해 스토어 트랜잭션을 영영 finish 못한다
        // (2026-07-20 audit B1-1, lossy sentinel). alreadyOwned 를 분리해 클라가 finish 하게 한다.
        // 단 ALREADY_PRESENT_UNSEEN_TOKEN(보유 중이지만 **다른** 미처리 토큰)은 alreadyOwned 로
        // 취급하면 안 된다 — 클라가 그 토큰을 finish 하면 기존 권리 환불 후 재시도 경로가 사라진다
        // (Codex R1 HIGH). REVOKED 와 함께 granted=false + alreadyOwned=false 유지.
        val granted = grantResult == GrantResult.GRANTED
        val alreadyOwned = grantResult == GrantResult.ALREADY_PRESENT
        auditService.publish(
            userId = userId,
            action = "BILLING_IAP_VERIFIED",
            resourceType = "Billing",
            payload =
                mapOf(
                    "product" to body.productId,
                    "entitlementKey" to entitlementKey,
                    "granted" to granted,
                    "alreadyOwned" to alreadyOwned,
                    "testMode" to testMode,
                ),
        )
        return ResponseEntity.ok(IapVerifyResponse(granted = granted, entitlementKey = entitlementKey, alreadyOwned = alreadyOwned))
    }

    /**
     * V36 재설계: tx_ref 충돌 audit — 서비스 tx 안 publish 는 롤백과 함께 유실되므로
     * (AFTER_COMMIT 미도달, fallbackExecution 은 tx 부재 시에만 동작) 컨트롤러가 tx 밖에서 기록.
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
}

data class IapVerifyRequest(
    val productId: String,
    val purchaseToken: String,
    val platform: String = "ANDROID",
    // iOS(App Store): base64 app receipt. ANDROID 는 불요(null). platform=IOS/APP_STORE 시 필수.
    val receipt: String? = null,
)

data class IapVerifyResponse(
    val granted: Boolean,
    val entitlementKey: String,
    // 멱등 재검증(이미 소유) — 클라는 granted 와 동일하게 성공 처리 + finish() 해야 한다.
    val alreadyOwned: Boolean = false,
)
