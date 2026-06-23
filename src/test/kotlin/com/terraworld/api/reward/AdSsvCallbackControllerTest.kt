package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
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

/**
 * P1-1 (출시 BLOCKING): AdMob SSV 콜백 적대적 단위 테스트 (공개·무인증 endpoint).
 *
 * 위조 SSV 방어 순서: malformed → missing-params → timestamp 신선도 → ad_unit allowlist →
 *   ECDSA 서명 → transaction_id 길이/ dedup. 각 거부 분기를 검증.
 */
class AdSsvCallbackControllerTest {
    private val verifier: AdSsvSignatureVerifier = mock()
    private val nonceInboxRepository: AdRewardNonceInboxRepository = mock()
    private val auditService: AuditService = mock()

    private fun controller(
        toleranceMs: Long = 300_000,
        allowlistCsv: String = "",
    ) = AdSsvCallbackController(verifier, nonceInboxRepository, auditService, toleranceMs, allowlistCsv)

    private fun request(
        signature: String? = "SIG",
        keyId: String? = "1",
        transactionId: String? = "tx-1",
        userId: String? = "user-1",
        timestamp: String? = null,
        adUnit: String? = null,
        includeSignatureInQuery: Boolean = true,
    ): MockHttpServletRequest {
        val req = MockHttpServletRequest()
        val qs = StringBuilder("transaction_id=${transactionId ?: ""}&user_id=${userId ?: ""}")
        if (includeSignatureInQuery) qs.append("&signature=${signature ?: ""}")
        req.queryString = qs.toString()
        signature?.let { req.setParameter("signature", it) }
        keyId?.let { req.setParameter("key_id", it) }
        transactionId?.let { req.setParameter("transaction_id", it) }
        userId?.let { req.setParameter("user_id", it) }
        timestamp?.let { req.setParameter("timestamp", it) }
        adUnit?.let { req.setParameter("ad_unit", it) }
        return req
    }

    @Test
    fun `signature param 없는 query 는 400 (malformed)`() {
        val res = controller().handleSsv(request(includeSignatureInQuery = false))
        assertEquals(400, res.statusCode.value())
    }

    @Test
    fun `필수 param(user_id) 누락은 400`() {
        val res = controller().handleSsv(request(userId = null))
        assertEquals(400, res.statusCode.value())
    }

    @Test
    fun `오래된 timestamp(skew tolerance 초과)는 403`() {
        // timestamp=0 (1970) → skew 막대 → tolerance(300s) 초과
        val res = controller(toleranceMs = 300_000).handleSsv(request(timestamp = "0"))
        assertEquals(403, res.statusCode.value())
        // 서명 검증까지 도달하지 않음
        verify(verifier, never()).verify(any(), any(), any())
    }

    @Test
    fun `ad_unit allowlist 외 값은 403`() {
        val res = controller(allowlistCsv = "ca-app/allowed").handleSsv(request(adUnit = "ca-app/evil"))
        assertEquals(403, res.statusCode.value())
    }

    @Test
    fun `서명 불일치는 403 + INVALID audit`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(false)
        val res = controller().handleSsv(request())
        assertEquals(403, res.statusCode.value())
        verify(auditService).publish(eq("user-1"), eq("REWARD_AD_SSV_INVALID"), anyOrNull(), anyOrNull(), anyOrNull())
        verify(nonceInboxRepository, never()).insertIfAbsent(any(), any(), any())
    }

    @Test
    fun `transaction_id 64자 초과는 400 (inbox 컬럼 truncation 방지)`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        val res = controller().handleSsv(request(transactionId = "a".repeat(65)))
        assertEquals(400, res.statusCode.value())
        verify(nonceInboxRepository, never()).insertIfAbsent(any(), any(), any())
    }

    @Test
    fun `이미 처리된 transaction(insertIfAbsent=0)은 200 dedup (replay 차단)`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceInboxRepository.insertIfAbsent(any(), any(), any())).thenReturn(0)
        val res = controller().handleSsv(request())
        assertEquals(200, res.statusCode.value())
    }

    @Test
    fun `정상 SSV 는 200 + VERIFIED audit + inbox 기록`() {
        whenever(verifier.verify(any(), any(), any())).thenReturn(true)
        whenever(nonceInboxRepository.insertIfAbsent(any(), any(), any())).thenReturn(1)
        val res = controller().handleSsv(request())
        assertEquals(200, res.statusCode.value())
        verify(auditService).publish(eq("user-1"), eq("REWARD_AD_SSV_VERIFIED"), anyOrNull(), anyOrNull(), anyOrNull())
    }
}
