package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * P1-1 (출시 BLOCKING): Google Play IAP 검증기 적대적 단위 테스트.
 *
 * verifyToken/resolve 는 실 Play Developer API HTTP 를 호출 → 보안 핵심인 parsePurchase
 * (purchaseState · obfuscatedExternalAccountId user 바인딩, IAP HIGH)를 internal seam 으로 검증.
 * 실 token 트래픽은 §7 핸드오프(service-account 주입 + 내부테스트 트랙 구매).
 */
class PlayPurchaseVerifierTest {
    private val om = ObjectMapper()

    private fun verifier(path: String = "") =
        PlayPurchaseVerifier(
            packageName = "app.terraworld.mobile",
            serviceAccountJsonPath = path,
            objectMapper = om,
        )

    // ─── isEnabled / 조기 반환 (HTTP 미도달) ──────────────────────

    @Test
    fun `isEnabled — service-account-json-path 유무로 결정`() {
        assertTrue(verifier(path = "/x/sa.json").isEnabled())
        assertFalse(verifier(path = "").isEnabled())
    }

    @Test
    fun `verifyToken — service-account 미설정이면 Disabled`() {
        assertEquals(VerificationResult.Disabled, verifier(path = "").verifyToken("p", "tok", "user-1"))
    }

    @Test
    fun `resolveObfuscatedAccountId — service-account 미설정이면 null`() {
        assertNull(verifier(path = "").resolveObfuscatedAccountId("p", "tok"))
    }

    // ─── parsePurchase (purchaseState + user 바인딩) ──────────────

    @Test
    fun `parsePurchase — purchaseState 0 아니면 invalidToken(purchase_state_X)`() {
        // 1 = CANCELED
        val result = verifier().parsePurchase("""{"purchaseState":1}""", "user-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("purchase_state_1", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `parsePurchase — obfuscatedExternalAccountId 부재면 거부(missing_obfuscated_account_id, replay 차단)`() {
        val result = verifier().parsePurchase("""{"purchaseState":0}""", "user-1")
        assertTrue(result is VerificationResult.InvalidToken)
        assertEquals("missing_obfuscated_account_id", (result as VerificationResult.InvalidToken).reason)
    }

    @Test
    fun `parsePurchase — obfuscated user 불일치면 UserMismatch(다른 user token replay)`() {
        val result =
            verifier().parsePurchase(
                """{"purchaseState":0,"obfuscatedExternalAccountId":"user-2"}""",
                "user-1",
            )
        assertTrue(result is VerificationResult.UserMismatch)
        result as VerificationResult.UserMismatch
        assertEquals("user-1", result.expected)
        assertEquals("user-2", result.actual)
    }

    @Test
    fun `parsePurchase — purchaseState 0 + user 일치면 Ok`() {
        val result =
            verifier().parsePurchase(
                """{"purchaseState":0,"obfuscatedExternalAccountId":"user-1"}""",
                "user-1",
            )
        assertEquals(VerificationResult.Ok, result)
    }
}
