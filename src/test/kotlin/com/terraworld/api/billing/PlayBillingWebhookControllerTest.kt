package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.api.entitlement.EntitlementTxRefConflictException
import com.terraworld.api.entitlement.GrantResult
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
 *  - PubSub: malformed base64/JSON 은 **200 ACK** (redelivery storm 차단)
 *  - BE-01: verify 는 tx 밖 / inbox dedup + grant 는 같은 짧은 tx — grant 실패는 5xx 전파
 *    (inbox 도 함께 rollback → 재전송이 정상 재처리, 200 삼키기 금지)
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
            // BE-01: 실제 tx 경계 bean 그대로 사용 (inbox dedup + action 원자성 로직 포함)
            BillingWebhookTxService(webhookInboxRepository),
            // BE-01: URL 미설정 alert 서비스 — sendDiscordAlert 는 skip 로그만 (@Async 는 단위 테스트에서 미적용)
            BillingAlertService(om, ""),
            expectedInternalToken = token,
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
        // BE-01: verify 가 dedup 앞으로 이동 — 중복 notification 도 verify 는 재수행 후 skip.
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(0)
        val res = controller().handleRtdn(token, rtdn())
        assertEquals(200, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `BE-01 — verifier Ok + grant 예외는 전파(5xx 재시도 계약, 200 삼키기 금지)`() {
        // grant 실패 시 BillingWebhookTxService 의 tx 가 inbox insert 와 함께 rollback →
        // 5xx 응답 → Pub/Sub/재전송이 정상 재처리. 200 으로 삼키면 권리 영구 유실.
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("db down"))
        org.junit.jupiter.api.assertThrows<RuntimeException> {
            controller().handleRtdn(token, rtdn())
        }
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
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.GRANTED)
        val res = controller().handleRtdn(token, rtdn(type = "ONE_TIME_PRODUCT_PURCHASED"))
        assertEquals(200, res.statusCode.value())
        verify(entitlementService).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `R4-ENT-01 — 이미 보유(ALREADY_PRESENT)는 기존 멱등 성공 시맨틱 유지 — 200 ACK`() {
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.ALREADY_PRESENT)
        val res = controller().handleRtdn(token, rtdn(type = "ONE_TIME_PRODUCT_PURCHASED"))
        assertEquals(200, res.statusCode.value())
    }

    @Test
    fun `R4-ENT-01 — grant TxRefConflict 는 예외 전파 (inbox tx rollback → 5xx → 재전송 재처리 계약)`() {
        // tx_ref 충돌은 정상 멱등이 아닌 실제 실패. 구 Boolean false 뭉갬은 inbox 가 함께 커밋돼
        // 200 → Pub/Sub 재전송이 duplicate 차단 → 결제 영구 유실이었다. 예외는 action 람다가
        // BillingWebhookTxService.processWithInboxDedup 의 @Transactional 안에서 던지므로 실제
        // 런타임에서는 inbox insert 까지 함께 롤백된다 (단위 테스트는 tx 미적용 — 전파를 pin).
        whenever(webhookInboxRepository.insertIfAbsent(any(), any())).thenReturn(1)
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.TX_REF_CONFLICT)
        org.junit.jupiter.api.assertThrows<EntitlementTxRefConflictException> {
            controller().handleRtdn(token, rtdn(type = "ONE_TIME_PRODUCT_PURCHASED"))
        }
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
    fun `pubsub malformed base64 는 200 ACK (redelivery storm 차단)`() {
        whenever(verifier.isEnabled()).thenReturn(true)
        // "!!!" 는 유효하지 않은 base64 → decode 시 IllegalArgumentException → 200 ACK
        val envelope = PubSubEnvelope(message = PubSubMessage(data = "!!!", messageId = "m-2"))
        val res = controller().handlePubSubRtdn(token, envelope)
        assertEquals(200, res.statusCode.value())
        // BE-01: decode 가 tx/dedup 이전으로 이동 — 200 ACK 자체가 재전송을 차단하므로
        // inbox 기록이 불필요 (기존 dedup 선커밋은 @Transactional 롤백 회피용이었음).
        verify(webhookInboxRepository, never()).insertIfAbsent(any(), any())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `pubsub 토큰 불일치면 401`() {
        assertEquals(401, controller().handlePubSubRtdn("wrong", PubSubEnvelope()).statusCode.value())
    }
}
