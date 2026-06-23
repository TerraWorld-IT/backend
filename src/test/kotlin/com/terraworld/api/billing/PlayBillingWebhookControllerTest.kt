package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.audit.AuditService
import com.terraworld.domain.billing.WebhookInboxRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import kotlin.test.assertEquals

/**
 * P1-1 (출시 BLOCKING): Play RTDN webhook 적대적 단위 테스트.
 *
 * 보안 핵심(은폐·무결제 grant·redelivery storm 방어):
 *  - 토큰 불일치/부재 → 401 (audit subject 는 "anonymous")
 *  - dedup(insertIfAbsent=0) → 200 skip (중복 재처리 방지)
 *  - verifier Disabled + prod → 503 (Google 검증 없이 grant 금지)
 *  - PubSub: verifier-disabled+prod 503 은 **dedup insert 前** (재전송 유도, 영구 은폐 차단)
 *  - PubSub: malformed base64/JSON 은 **200 ACK** (throw 시 롤백→무한 redelivery storm 차단)
 */
class PlayBillingWebhookControllerTest {
    private val entitlementService: EntitlementService = mock()
    private val verifier: PlayPurchaseVerifier = mock()
    private val auditService: AuditService = mock()
    private val webhookInboxRepository: WebhookInboxRepository = mock()
    private val environment: Environment = mock()
    private val om = ObjectMapper()
    private val token = "internal-secret"

    private fun controller() =
        PlayBillingWebhookController(
            entitlementService,
            verifier,
            auditService,
            om,
            environment,
            webhookInboxRepository,
            expectedInternalToken = token,
            alertDiscordWebhook = "",
        )

    @BeforeEach
    fun base() {
        whenever(environment.activeProfiles).thenReturn(arrayOf()) // non-prod 기본
    }

    private fun rtdn(
        type: String = "ONE_TIME_PRODUCT_PURCHASED",
        productId: String = "free_placement_unlock",
        notificationId: String? = "n-1",
    ) = PlayBillingNotification(
        userId = "user-1",
        notificationType = type,
        productId = productId,
        purchaseToken = "ptoken-1",
        notificationId = notificationId,
    )

    // ─── handleRtdn 인증 ──────────────────────────────────────────

    @Test
    fun `토큰 부재면 401 + grant 미발생`() {
        val res = controller().handleRtdn(null, rtdn())
        assertEquals(401, res.statusCode.value())
        verify(webhookInboxRepository, never()).insertIfAbsent(any(), any())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `토큰 불일치면 401`() {
        val res = controller().handleRtdn("wrong-token", rtdn())
        assertEquals(401, res.statusCode.value())
    }

    // ─── dedup ───────────────────────────────────────────────────

    @Test
    fun `dedup — 이미 처리된 notification(insertIfAbsent=0)은 200 skip + grant 미발생`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(0)
        val res = controller().handleRtdn(token, rtdn())
        assertEquals(200, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    // ─── verifier 결과 분기 ──────────────────────────────────────

    @Test
    fun `verifier Disabled + prod 는 503 (Google 검증 없이 grant 금지)`() {
        whenever(environment.activeProfiles).thenReturn(arrayOf("prod"))
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Disabled)
        val res = controller().handleRtdn(token, rtdn())
        assertEquals(503, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `verifier InvalidToken 은 400`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.invalidToken("bad"))
        assertEquals(400, controller().handleRtdn(token, rtdn()).statusCode.value())
    }

    @Test
    fun `verifier UserMismatch 는 401`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.userMismatch("user-1", "user-2"))
        assertEquals(401, controller().handleRtdn(token, rtdn()).statusCode.value())
    }

    @Test
    fun `verifier Ok + PURCHASED 면 grant 호출 + 200`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        val res = controller().handleRtdn(token, rtdn(type = "ONE_TIME_PRODUCT_PURCHASED"))
        assertEquals(200, res.statusCode.value())
        verify(entitlementService).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `verifier Ok + REFUND 면 revoke 호출`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        controller().handleRtdn(token, rtdn(type = "REFUND"))
        verify(entitlementService).revoke(any(), any(), any(), anyOrNull())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `unknown product 는 200 + grant 미발생`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        val res = controller().handleRtdn(token, rtdn(productId = "unknown_sku"))
        assertEquals(200, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    // ─── handlePubSubRtdn — 핵심 ordering ────────────────────────

    @Test
    fun `pubsub verifier-disabled + prod 는 503 (dedup insert 前 — 재전송 유도)`() {
        whenever(environment.activeProfiles).thenReturn(arrayOf("prod"))
        whenever(verifier.isEnabled()).thenReturn(false)
        val envelope = PubSubEnvelope(message = PubSubMessage(data = "abc", messageId = "m-1"))
        val res = controller().handlePubSubRtdn(token, envelope)
        assertEquals(503, res.statusCode.value())
        // 503 은 dedup insert *이전* — 재전송이 dup-skip 으로 영구 은폐되지 않아야 함
        verify(webhookInboxRepository, never()).insertIfAbsent(any(), any())
    }

    @Test
    fun `pubsub malformed base64 는 200 ACK (롤백→redelivery storm 차단)`() {
        whenever(verifier.isEnabled()).thenReturn(true)
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        // "!!!" 는 유효하지 않은 base64 → decode 시 IllegalArgumentException → 200 ACK
        val envelope = PubSubEnvelope(message = PubSubMessage(data = "!!!", messageId = "m-2"))
        val res = controller().handlePubSubRtdn(token, envelope)
        assertEquals(200, res.statusCode.value())
        // messageId dedup 은 이미 기록(ACK) — 재전송 차단
        verify(webhookInboxRepository).insertIfAbsent(any(), any())
    }

    @Test
    fun `pubsub 토큰 불일치면 401`() {
        assertEquals(401, controller().handlePubSubRtdn("wrong", PubSubEnvelope()).statusCode.value())
    }
}
