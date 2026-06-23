package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P1-1 (출시 BLOCKING): Apple App Store IAP 검증기 적대적 단위 테스트.
 *
 * "녹색 빌드 ≠ 결함 없음" — workflow-batch 로 구현된 결제 검증 로직의 경계/정책 결함을 노린다.
 * verifyToken 은 실 HTTP(Apple verifyReceipt)를 호출하므로, 보안 핵심 로직인
 *  - shouldBlockSandboxOnProd (21007 sandbox→prod 무결제 grant 표면, IAP-005)
 *  - evaluate (status/bundle/transaction 매칭·환불, IAP-001/002)
 * 를 internal seam 으로 직접 검증한다. HTTP 가 필요한 경로는 §7 핸드오프(실 sandbox 구매).
 */
class AppStorePurchaseVerifierTest {
    private val om = ObjectMapper()
    private val bundleId = "app.terraworld.mobile"

    private fun verifier(
        secret: String = "shared-secret",
        allowSandboxFallback: Boolean = true,
    ) = AppStorePurchaseVerifier(
        sharedSecret = secret,
        bundleId = bundleId,
        allowSandboxFallback = allowSandboxFallback,
        objectMapper = om,
    )

    // ─── isEnabled / 조기 반환 (HTTP 미도달) ──────────────────────

    @Test
    fun `isEnabled — shared-secret 유무로 결정`() {
        assertTrue(verifier(secret = "x").isEnabled())
        assertFalse(verifier(secret = "").isEnabled())
    }

    @Test
    fun `verifyToken — shared-secret 미설정이면 Disabled (live 경로 fail-closed 위임)`() {
        val result = verifier(secret = "").verifyToken("free_placement_unlock", "tx-1", "receipt")
        assertEquals(VerificationResult.Disabled, result)
    }

    @Test
    fun `verifyToken — receipt 빈 문자열이면 invalidToken(missing_receipt)`() {
        val result = verifier(secret = "x").verifyToken("free_placement_unlock", "tx-1", "")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("missing_receipt", (result as VerificationResult.InvalidToken).reason)
    }

    // ─── shouldBlockSandboxOnProd (IAP-005 — 핵심 보안 가드) ────────

    @Test
    fun `shouldBlockSandboxOnProd — 21007 + fallback 미허용이면 차단(prod 무결제 grant 방지)`() {
        assertTrue(verifier(allowSandboxFallback = false).shouldBlockSandboxOnProd(21007))
    }

    @Test
    fun `shouldBlockSandboxOnProd — 21007 + fallback 허용이면 미차단(sandbox 재시도)`() {
        assertFalse(verifier(allowSandboxFallback = true).shouldBlockSandboxOnProd(21007))
    }

    @Test
    fun `shouldBlockSandboxOnProd — 정상 status(0)는 차단 안 함`() {
        assertFalse(verifier(allowSandboxFallback = false).shouldBlockSandboxOnProd(0))
        assertFalse(verifier(allowSandboxFallback = false).shouldBlockSandboxOnProd(null))
    }

    // ─── evaluate (status / bundle / transaction 매칭) ────────────

    @Test
    fun `evaluate — status 0 아니면 invalidToken(apple_status_X)`() {
        val node = om.readTree("""{"status":21003}""")
        val result = verifier().evaluate(node, "free_placement_unlock", "tx-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("apple_status_21003", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `evaluate — bundle_id 불일치는 거부(다른 앱 영수증 replay 차단)`() {
        val node =
            om.readTree(
                """{"status":0,"receipt":{"bundle_id":"com.evil.app","in_app":[{"product_id":"free_placement_unlock","transaction_id":"tx-1"}]}}""",
            )
        val result = verifier().evaluate(node, "free_placement_unlock", "tx-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("bundle_missing_or_mismatch", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `evaluate — bundle_id 부재(null)도 거부(fail-closed)`() {
        val node = om.readTree("""{"status":0,"receipt":{"in_app":[]}}""")
        val result = verifier().evaluate(node, "free_placement_unlock", "tx-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("bundle_missing_or_mismatch", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `evaluate — product+transaction 일치 + 미환불이면 Ok`() {
        val node =
            om.readTree(
                """{"status":0,"receipt":{"bundle_id":"$bundleId","in_app":[{"product_id":"free_placement_unlock","transaction_id":"tx-1"}]}}""",
            )
        assertEquals(VerificationResult.Ok, verifier().evaluate(node, "free_placement_unlock", "tx-1"))
    }

    @Test
    fun `evaluate — transaction_id 불일치면 transaction_not_found(부정확 매칭 차단)`() {
        val node =
            om.readTree(
                """{"status":0,"receipt":{"bundle_id":"$bundleId","in_app":[{"product_id":"free_placement_unlock","transaction_id":"OTHER"}]}}""",
            )
        val result = verifier().evaluate(node, "free_placement_unlock", "tx-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("transaction_not_found", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `evaluate — 매칭되나 환불(cancellation_date)이면 transaction_not_found`() {
        val node =
            om.readTree(
                """{"status":0,"receipt":{"bundle_id":"$bundleId","in_app":[{"product_id":"free_placement_unlock","transaction_id":"tx-1","cancellation_date":"2026-01-01"}]}}""",
            )
        val result = verifier().evaluate(node, "free_placement_unlock", "tx-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("transaction_not_found", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `evaluate — latest_receipt_info 에서도 매칭 허용`() {
        val node =
            om.readTree(
                """{"status":0,"receipt":{"bundle_id":"$bundleId"},"latest_receipt_info":[{"product_id":"free_placement_unlock","transaction_id":"tx-1"}]}""",
            )
        assertEquals(VerificationResult.Ok, verifier().evaluate(node, "free_placement_unlock", "tx-1"))
    }
}
