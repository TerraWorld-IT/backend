package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class AdSsvCallbackControllerTest {
    private val verifier: AdSsvSignatureVerifier = mock()
    private val nonceService: AdRewardNonceService = mock()
    private val auditService: AuditService = mock()

    private fun controller(
        mode: String = "legacy",
        toleranceMs: Long = 300_000,
        allowlistCsv: String = "",
    ) = AdSsvCallbackController(
        verifier,
        nonceService,
        auditService,
        AdRewardPolicy(mode),
        toleranceMs,
        allowlistCsv,
    )

    private fun request(
        signature: String? = "SIG",
        keyId: String? = "1",
        transactionId: String? = "tx-1",
        userId: String? = "user-1",
        timestamp: String? = System.currentTimeMillis().toString(),
        adUnit: String? = "ca-app/allowed",
        customData: String? = VALID_NONCE,
        includeSignatureInQuery: Boolean = true,
        unsignedSuffix: String? = null,
    ): MockHttpServletRequest {
        val request = MockHttpServletRequest()
        val signedParts = mutableListOf("transaction_id=${transactionId ?: ""}", "user_id=${userId ?: ""}")
        timestamp?.let { signedParts += "timestamp=$it" }
        customData?.let { signedParts += "custom_data=$it" }
        adUnit?.let { signedParts += "ad_unit=$it" }
        val query = StringBuilder(signedParts.joinToString("&"))
        if (includeSignatureInQuery) {
            query.append("&signature=${signature ?: ""}&key_id=${keyId ?: ""}")
        }
        unsignedSuffix?.let { query.append("&$it") }
        request.queryString = query.toString()

        signature?.let { request.setParameter("signature", it) }
        keyId?.let { request.setParameter("key_id", it) }
        transactionId?.let { request.setParameter("transaction_id", it) }
        userId?.let { request.setParameter("user_id", it) }
        timestamp?.let { request.setParameter("timestamp", it) }
        adUnit?.let { request.setParameter("ad_unit", it) }
        customData?.let { request.setParameter("custom_data", it) }
        return request
    }

    @Test
    fun `signature param 없는 query 는 400`() {
        val response = controller().handleSsv(request(includeSignatureInQuery = false))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `key_id 뒤 unsigned query param 이 붙으면 400`() {
        val response = controller().handleSsv(request(unsignedSuffix = "user_id=attacker"))
        assertEquals(400, response.statusCode.value())
        verify(verifier, never()).verify(any(), any(), any())
    }

    @Test
    fun `필수 timestamp 누락은 400`() {
        val response = controller().handleSsv(request(timestamp = null))
        assertEquals(400, response.statusCode.value())
    }

    @Test
    fun `오래된 timestamp 는 403 이며 서명을 검증하지 않는다`() {
        val response = controller().handleSsv(request(timestamp = "0"))
        assertEquals(403, response.statusCode.value())
        verify(verifier, never()).verify(any(), any(), any())
    }

    @Test
    fun `authoritative 에서 ad unit allowlist 미설정은 503 fail closed`() {
        val response = controller(mode = "authoritative").handleSsv(request())
        assertEquals(503, response.statusCode.value())
        verify(verifier, never()).verify(any(), any(), any())
    }

    @Test
    fun `allowlist 에 없는 ad unit 은 403`() {
        val response = controller(allowlistCsv = "ca-app/allowed").handleSsv(request(adUnit = "ca-app/other"))
        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `서명 불일치는 403 과 INVALID audit 으로 관측된다`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(false)

        val response = controller(mode = "shadow").handleSsv(request())

        assertEquals(403, response.statusCode.value())
        verify(auditService).publish(
            eq("user-1"),
            eq("REWARD_AD_SSV_INVALID"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
        verify(nonceService, never()).recordVerifiedSsv(any(), any(), anyOrNull())
    }

    @Test
    fun `서명 대상은 signature 직전까지의 raw query 순서를 그대로 보존한다`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceService.recordVerifiedSsv(any(), any(), anyOrNull())).thenReturn(
            SsvNonceOutcome(SsvNonceResult.RECORDED),
        )
        val timestamp = System.currentTimeMillis().toString()

        val response = controller().handleSsv(request(timestamp = timestamp))

        assertEquals(200, response.statusCode.value())
        verify(verifier).verify(
            "transaction_id=tx-1&user_id=user-1&timestamp=$timestamp&custom_data=$VALID_NONCE&ad_unit=ca-app/allowed",
            "SIG",
            1L,
        )
    }

    @Test
    fun `shadow 의 미결합 custom_data 는 지급을 막지 않고 UNMATCHED audit 을 남긴다`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceService.recordVerifiedSsv(any(), any(), anyOrNull())).thenReturn(
            SsvNonceOutcome(SsvNonceResult.UNMATCHED),
        )

        val response = controller(mode = "shadow").handleSsv(request())

        assertEquals(200, response.statusCode.value())
        verify(auditService).publish(
            eq("user-1"),
            eq("REWARD_AD_SSV_UNMATCHED"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
    }

    @Test
    fun `authoritative 의 미결합 custom_data 는 403 fail closed`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceService.recordVerifiedSsv(any(), any(), anyOrNull())).thenReturn(
            SsvNonceOutcome(SsvNonceResult.UNMATCHED),
        )

        val response =
            controller(mode = "authoritative", allowlistCsv = "ca-app/allowed")
                .handleSsv(request())

        assertEquals(403, response.statusCode.value())
    }

    @Test
    fun `authoritative 의 서버 nonce 결합 성공은 200 과 VERIFIED audit`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceService.recordVerifiedSsv(any(), any(), anyOrNull())).thenReturn(
            SsvNonceOutcome(SsvNonceResult.VERIFIED, "AD_REWARD"),
        )

        val response =
            controller(mode = "authoritative", allowlistCsv = "ca-app/allowed")
                .handleSsv(request())

        assertEquals(200, response.statusCode.value())
        verify(auditService).publish(
            eq("user-1"),
            eq("REWARD_AD_SSV_VERIFIED"),
            anyOrNull(),
            anyOrNull(),
            anyOrNull(),
        )
    }

    @Test
    fun `이미 처리한 transaction callback 은 200 멱등 응답`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceService.recordVerifiedSsv(any(), any(), anyOrNull())).thenReturn(
            SsvNonceOutcome(SsvNonceResult.DUPLICATE, "AD_REWARD"),
        )

        val response = controller().handleSsv(request())

        assertEquals(200, response.statusCode.value())
    }

    companion object {
        private const val VALID_NONCE = "550e8400-e29b-41d4-a716-446655440000"
    }
}
