package com.terraworld.api.reward

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.springframework.mock.web.MockHttpServletRequest

/**
 * SSV 콜백 컨트롤러 흐름 테스트. 서명 검증기/리포지토리는 mock — 컨트롤러의 분기(400/403/200·dedup·unattributed)만 검증.
 * (실제 crypto 는 AdSsvSignatureVerifierTest, 실연동은 G2/G3 인간 게이트.)
 */
class AdSsvCallbackControllerTest {
    private val verifier = Mockito.mock(AdSsvSignatureVerifier::class.java)
    private val repo = Mockito.mock(AdRewardNonceInboxRepository::class.java)
    private val audit = Mockito.mock(AuditService::class.java)
    private val controller = AdSsvCallbackController(verifier, repo, audit, emptyList())

    private fun req(query: String?): MockHttpServletRequest = MockHttpServletRequest("GET", "/api/v1/rewards/ad/ssv-callback").apply { queryString = query }

    private fun stubVerify(result: Boolean) {
        Mockito.`when`(verifier.verify(anyString(), anyString(), anyLong())).thenReturn(result)
    }

    @Test
    fun `query 없으면 400`() {
        assertEquals(400, controller.handleSsvCallback(req(null)).statusCode.value())
    }

    @Test
    fun `signature 파라미터 없으면 400`() {
        assertEquals(400, controller.handleSsvCallback(req("transaction_id=t1&user_id=u1")).statusCode.value())
    }

    @Test
    fun `transaction_id 누락 시 400`() {
        assertEquals(
            400,
            controller.handleSsvCallback(req("ad_unit=x&user_id=u1&signature=sig&key_id=1")).statusCode.value(),
        )
    }

    @Test
    fun `서명 무효면 403`() {
        stubVerify(false)
        val resp = controller.handleSsvCallback(req("ad_unit=x&transaction_id=t1&user_id=u1&signature=sig&key_id=1"))
        assertEquals(403, resp.statusCode.value())
    }

    @Test
    fun `유효 서명 + 신규 transaction 이면 200`() {
        stubVerify(true)
        Mockito.`when`(repo.insertSsvIfAbsent(anyString(), anyString(), anyString())).thenReturn(1)
        val resp = controller.handleSsvCallback(req("ad_unit=x&transaction_id=t1&user_id=u1&signature=sig&key_id=1"))
        assertEquals(200, resp.statusCode.value())
    }

    @Test
    fun `중복 transaction(재시도)이면 200 idempotent`() {
        stubVerify(true)
        Mockito.`when`(repo.insertSsvIfAbsent(anyString(), anyString(), anyString())).thenReturn(0)
        val resp = controller.handleSsvCallback(req("ad_unit=x&transaction_id=t1&user_id=u1&signature=sig&key_id=1"))
        assertEquals(200, resp.statusCode.value())
    }

    @Test
    fun `user_id 귀속 불가 시 200 + dedup insert 미호출`() {
        stubVerify(true)
        val resp = controller.handleSsvCallback(req("ad_unit=x&transaction_id=t1&signature=sig&key_id=1"))
        assertEquals(200, resp.statusCode.value())
        Mockito.verify(repo, Mockito.never()).insertSsvIfAbsent(anyString(), anyString(), anyString())
    }
}
