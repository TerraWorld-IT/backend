package com.terraworld.api.billing

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * Apple App Store(StoreKit) IAP 영수증(receipt) 검증. (2026-06-23, iOS 결제 지원 — 사용자 결정)
 *
 * [PlayPurchaseVerifier] 의 자매 — 동일한 fail-closed 패턴 + [VerificationResult] 재사용 + 신규 의존성 0
 * (JDK `java.net.http` + 기존 Jackson). client(usePayment, iOS 분기)가 StoreKit 구매 후 base64 app
 * receipt 와 transactionId 를 backend 로 전달 → 본 verifier 가 Apple `verifyReceipt` 로 진위 확인.
 *
 *   POST https://buy.itunes.apple.com/verifyReceipt   (prod)
 *   body: {"receipt-data": <base64 receipt>, "password": <shared-secret>, "exclude-old-transactions": false}
 *   status 0 = 유효. 21007 = sandbox 영수증을 prod 로 보냄 → sandbox 재시도(Apple 권장: prod 선시도 후 fallback).
 *
 * 검증 항목: status==0 + receipt.bundle_id == 기대 bundleId + in_app/latest_receipt_info 중
 *   (product_id == productId AND transaction_id == 전달 transactionId) 인 항목 존재 + 환불(cancellation_date) 아님.
 *
 * gating: `terraworld.billing.apple.shared-secret` 미설정 시 [VerificationResult.disabled]
 *   → caller(IapVerifyController) 가 live 경로에서 grant 거부(fail-closed). 운영(prod)은 주입 필수.
 *
 * ⚠️ 한계(정직 표기): legacy `verifyReceipt` 는 StoreKit2 `appAccountToken`(구매자 user 바인딩)을
 *   안정적으로 노출하지 않아, Play 의 obfuscatedExternalAccountId 처럼 user cross-check 를 강제할 수 없다.
 *   대신 caller 가 transactionId 를 txRef 로 사용 → entitlement 의 글로벌 unique(uq_user_entitlement_tx_ref)
 *   가 동일 영수증의 중복/cross-user grant 를 1회로 제한(bounded). 완전한 user 바인딩은 App Store Server
 *   API(JWS signed transaction + appAccountToken) 마이그레이션이 필요 — 후속.
 * ⚠️ 실 영수증 트래픽 미검증(.env-ready) — shared-secret 주입 + 실기기 sandbox 구매 후 1회 검증 필요.
 */
@Component
class AppStorePurchaseVerifier(
    @Value("\${terraworld.billing.apple.shared-secret:}")
    private val sharedSecret: String,
    @Value("\${terraworld.billing.apple.bundle-id:app.terraworld.mobile}")
    private val bundleId: String,
    // 21007(sandbox→prod) 시 sandbox 재검증은 Apple 공식 권장 플로우(prod 선시도 후 fallback)이며,
    // App Review 가 prod 앱에 sandbox 영수증으로 IAP 를 테스트하므로 기본 true 가 필요하다. sandbox
    // 구매는 sandbox tester Apple ID 가 있어야 가능하고, evaluate() 가 bundle/product/transaction 을
    // 모두 검증 + entitlement 의 글로벌 unique tx_ref 가 중복/cross-user grant 를 1회로 bound 하므로
    // "무결제 grant" 표면은 제한적이다. 그래도 prod 에서 sandbox 경유 grant 는 가시화한다(아래 warn).
    // App Review 종료 후 운영 강화 시 false override 가능(리뷰 IAP-005 / H4 code-review).
    @Value("\${terraworld.billing.apple.allow-sandbox-fallback:true}")
    private val allowSandboxFallback: Boolean,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    companion object {
        private const val PROD_URL = "https://buy.itunes.apple.com/verifyReceipt"
        private const val SANDBOX_URL = "https://sandbox.itunes.apple.com/verifyReceipt"

        // Apple status: 0=valid, 21007=sandbox receipt sent to prod(→sandbox 재시도), 21008=prod→sandbox.
        private const val STATUS_OK = 0
        private const val STATUS_SANDBOX_ON_PROD = 21007
    }

    /** service 미설정(개발/test-mode) 과 검증 결과 invalid 를 구분 (PlayPurchaseVerifier 와 동일 계약). */
    fun isEnabled(): Boolean = sharedSecret.isNotBlank()

    /**
     * @param productId Apple App Store 상품 ID (ProductEntitlementMapper 와 정합).
     * @param transactionId StoreKit 트랜잭션 ID — 영수증 in_app 에 존재해야 하며, caller 의 txRef(멱등 키)로도 사용.
     * @param receiptData base64 app receipt (client 가 StoreKit 에서 추출해 전달).
     */
    fun verifyToken(
        productId: String,
        transactionId: String,
        receiptData: String,
    ): VerificationResult {
        if (sharedSecret.isBlank()) {
            log.info("billing.apple.verify.disabled — shared-secret 없음(개발/test-mode 경로)")
            return VerificationResult.disabled()
        }
        if (receiptData.isBlank()) {
            return VerificationResult.invalidToken("missing_receipt")
        }
        return try {
            val prodResponse = postVerify(PROD_URL, receiptData)
            val prodStatus = prodResponse.get("status")?.asInt()
            // 21007: sandbox 영수증을 prod 로 보냄 → (허용 시) sandbox 재검증(Apple 권장). 미허용 시 거부.
            val node =
                if (prodStatus == STATUS_SANDBOX_ON_PROD) {
                    if (shouldBlockSandboxOnProd(prodStatus)) {
                        log.warn("billing.apple.verify.sandbox-on-prod-blocked product={}", productId)
                        return VerificationResult.invalidToken("sandbox_receipt_on_prod")
                    }
                    // H4 (code-review): prod 에서 sandbox 영수증으로 grant 진행 — 가시화(이상 트래픽 모니터링).
                    log.warn("billing.apple.verify.sandbox-fallback-grant product={} (sandbox 영수증 prod 검증)", productId)
                    postVerify(SANDBOX_URL, receiptData)
                } else {
                    prodResponse
                }
            evaluate(node, productId, transactionId)
        } catch (e: Exception) {
            // 검증 불능 = 안전하게 invalidToken(grant 금지). caller 가 4xx. stacktrace 보존(진단).
            log.error("billing.apple.verify.error product={}", productId, e)
            VerificationResult.invalidToken("verify_exception")
        }
    }

    private fun postVerify(
        url: String,
        receiptData: String,
    ): JsonNode {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "receipt-data" to receiptData,
                    "password" to sharedSecret,
                    "exclude-old-transactions" to false,
                ),
            )
        val req =
            HttpRequest
                .newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build()
        val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) {
            // Apple endpoint HTTP 오류 — 검증 불능.
            throw IllegalStateException("apple_http_${res.statusCode()}")
        }
        return objectMapper.readTree(res.body())
    }

    /**
     * 21007(sandbox 영수증을 prod 로) 응답을 sandbox 재시도 없이 차단할지 결정.
     * `allow-sandbox-fallback=false`(출시 후 권장) 이면 prod 무결제 grant 표면을 막기 위해 block.
     * (테스트 가능하도록 분리 — IAP-005 보안 가드)
     */
    internal fun shouldBlockSandboxOnProd(prodStatus: Int?): Boolean = prodStatus == STATUS_SANDBOX_ON_PROD && !allowSandboxFallback

    internal fun evaluate(
        node: JsonNode,
        productId: String,
        transactionId: String,
    ): VerificationResult {
        val status = node.get("status")?.asInt() ?: -1
        if (status != STATUS_OK) {
            return VerificationResult.invalidToken("apple_status_$status")
        }

        // bundle_id 검증 — 다른 앱 영수증 replay 차단. 부재(null/blank)도 거부(fail-closed, 리뷰 IAP-001).
        val receiptBundleId = node.path("receipt").get("bundle_id")?.asText()
        if (receiptBundleId.isNullOrBlank() || receiptBundleId != bundleId) {
            log.warn("billing.apple.verify.bundle-mismatch expected={} actual={}", bundleId, receiptBundleId)
            return VerificationResult.invalidToken("bundle_missing_or_mismatch")
        }

        // in_app + latest_receipt_info 양쪽에서 (product_id, transaction_id) 매칭 + 환불 아님.
        // transaction_id 정확 일치만 허용. original_transaction_id fallback 은 client 가 다른 식별자를
        // txRef 로 제출해 글로벌 unique 를 우회하는 cross-user replay 표면을 넓혀 제거(리뷰 IAP-002).
        val candidates = collectTransactions(node)
        val matched =
            candidates.any { tx ->
                val pid = tx.get("product_id")?.asText()
                val txId = tx.get("transaction_id")?.asText()
                val refunded = tx.get("cancellation_date")?.asText()?.isNotBlank() == true
                pid == productId && !refunded && txId == transactionId
            }
        return if (matched) {
            VerificationResult.ok()
        } else {
            VerificationResult.invalidToken("transaction_not_found")
        }
    }

    /** receipt.in_app + latest_receipt_info 의 트랜잭션 노드를 합쳐 반환(non-consumable 은 보통 in_app). */
    private fun collectTransactions(node: JsonNode): List<JsonNode> {
        val result = mutableListOf<JsonNode>()
        node
            .path("receipt")
            .path("in_app")
            .takeIf { it.isArray }
            ?.forEach { result.add(it) }
        node.path("latest_receipt_info").takeIf { it.isArray }?.forEach { result.add(it) }
        return result
    }
}
