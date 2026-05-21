package com.terraworld.api.billing

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * UltraPlan v3 — Codex audit PAY-002 fix (2026-05-18, HIGH):
 *
 * Google Play Developer API 로 purchaseToken 의 진위 + user 매핑 검증.
 *
 * 본 cycle = skeleton + interface. 실 API 호출은 별 cycle 에서 `google-api-services-androidpublisher`
 * SDK + Firebase service account JSON 으로 활성화 (E-FIREBASE-001 + Play Console service account 의존).
 *
 * 보안 흐름 (별 cycle 활성화 후):
 *  1) RTDN webhook 수신 → purchaseToken 추출
 *  2) Play API GET /androidpublisher/v3/applications/{packageName}/purchases/products/{productId}/tokens/{token}
 *     → purchaseState + developerPayload 응답
 *  3) developerPayload (또는 별 user_purchase_ledger) 의 userId 와 webhook body 의 userId 일치 검증
 *  4) 불일치 시 401 + audit "billing.verify.mismatch" 영구 기록
 *  5) 일치 + purchaseState=0(PURCHASED) 시 EntitlementService.grant 호출
 *
 * 본 cycle 의 skeleton 은 disabled 모드 — 별 secret 주입 시 활성화.
 */
@Component
class PlayPurchaseVerifier(
    @Value("\${terraworld.billing.play.package-name:com.terraworld.app}")
    private val packageName: String,
    @Value("\${terraworld.billing.play.service-account-json-path:}")
    private val serviceAccountJsonPath: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * @return [VerificationResult] purchase 의 발신자 (Google) 가 확인한 user/product 매핑.
     *   별 cycle 의 실 API 활성화 전까지는 [VerificationResult.disabled] 반환 — caller (WebhookController) 가
     *   본 결과를 보고 grant/revoke 진행 여부 결정.
     */
    fun verifyToken(
        productId: String,
        purchaseToken: String,
        expectedUserId: String,
    ): VerificationResult {
        if (serviceAccountJsonPath.isBlank()) {
            // 별 secret 미주입 — 본 cycle 의 운영 모드 (E-FIREBASE-001 미배포)
            log.info(
                "billing.verify.disabled — service-account-json-path 없음. " +
                    "운영 시 별 secret 주입 + 본 verifier 활성화 필수 (PAY-002 HIGH)",
            )
            return VerificationResult.disabled()
        }

        // TODO (별 cycle, E-FIREBASE-001 의존):
        //   1) google-api-services-androidpublisher SDK + GoogleCredentials.fromStream(FileInputStream(serviceAccountJsonPath))
        //   2) AndroidPublisher.Purchases.Products().get(packageName, productId, purchaseToken).execute()
        //   3) response.purchaseState 0(PURCHASED) / 1(CANCELED) / 2(PENDING) 분기
        //   4) response.developerPayload 또는 별 ledger 의 user 매핑 cross-check
        //   5) 불일치 시 VerificationResult.userMismatch(expected, actual)
        log.warn("billing.verify.todo productId={} — 별 cycle 실 API 호출 필요", productId)
        return VerificationResult.disabled()
    }
}

/**
 * Verification 결과 — caller 가 grant/revoke 진입 여부 결정.
 *
 *  - [ok]: Google API 확인 + user 매핑 일치 → grant/revoke 진행
 *  - [disabled]: 본 cycle 운영 모드 (별 secret 미주입) — caller 가 권한 검증 X로 진행
 *    (개발 환경 / 본 cycle 의 사용자 메타 결정 § 4.2.A 권장 default: revoke 보수적 적용 + alert)
 *  - [userMismatch]: user 매핑 불일치 — caller 가 401 + audit 기록
 *  - [invalidToken]: purchaseToken 무효 / 만료 — caller 가 400 + audit
 */
sealed class VerificationResult {
    object Ok : VerificationResult()

    object Disabled : VerificationResult()

    data class UserMismatch(
        val expected: String,
        val actual: String,
    ) : VerificationResult()

    data class InvalidToken(
        val reason: String,
    ) : VerificationResult()

    companion object {
        fun ok(): VerificationResult = Ok

        fun disabled(): VerificationResult = Disabled

        fun userMismatch(
            expected: String,
            actual: String,
        ): VerificationResult = UserMismatch(expected, actual)

        fun invalidToken(reason: String): VerificationResult = InvalidToken(reason)
    }
}
