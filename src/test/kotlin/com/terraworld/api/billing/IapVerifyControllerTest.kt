package com.terraworld.api.billing

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.api.entitlement.EntitlementTxRefConflictException
import com.terraworld.api.entitlement.GrantResult
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.security.AuthenticatedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.core.env.Environment
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P1-1 (출시 BLOCKING): IAP 검증 endpoint 적대적 단위 테스트 — fail-closed 보장.
 *
 * "녹색 빌드 ≠ 결함 없음" — 무료 grant 사고로 직결되는 경계:
 *  - live(test-mode=false) + verifier Disabled = config 오류 → grant 금지(INVALID_INPUT)
 *  - prod + test-mode=true = 무료지급 금지(INVALID_INPUT)
 *  - 알 수 없는 platform / productId / 빈·과길이 token 거부
 */
class IapVerifyControllerTest {
    private val verifier: PlayPurchaseVerifier = mock()
    private val appStoreVerifier: AppStorePurchaseVerifier = mock()
    private val entitlementService: EntitlementService = mock()
    private val auditService: AuditService = mock()
    private val environment: Environment = mock()

    private fun controller(testMode: Boolean = false) = IapVerifyController(verifier, appStoreVerifier, entitlementService, auditService, environment, testMode)

    @BeforeEach
    fun setupAuth() {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(AuthenticatedUser("user-1", "u@e.com"), null, emptyList())
        whenever(environment.activeProfiles).thenReturn(arrayOf()) // 기본: non-prod
    }

    @AfterEach
    fun clearAuth() = SecurityContextHolder.clearContext()

    private fun req(
        productId: String = "free_placement_unlock",
        token: String = "ptoken-1",
        platform: String = "ANDROID",
        receipt: String? = null,
    ) = IapVerifyRequest(productId, token, platform, receipt)

    // ─── 입력 검증 (INVALID_INPUT) ────────────────────────────────

    @Test
    fun `알 수 없는 productId 는 INVALID_INPUT`() {
        val ex = assertThrows<BusinessException> { controller().verify(req(productId = "unknown_sku")) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `purchaseToken 빈 문자열은 INVALID_INPUT`() {
        val ex = assertThrows<BusinessException> { controller().verify(req(token = "")) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `purchaseToken 128자 초과는 INVALID_INPUT(tx_ref 컬럼 truncation 방지)`() {
        val ex = assertThrows<BusinessException> { controller().verify(req(token = "a".repeat(129))) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `iOS 인데 receipt 부재면 INVALID_INPUT`() {
        val ex = assertThrows<BusinessException> { controller().verify(req(platform = "IOS", receipt = null)) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `알 수 없는 platform 은 INVALID_INPUT(silent 미스라우팅 금지)`() {
        val ex = assertThrows<BusinessException> { controller().verify(req(platform = "WINDOWS")) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    // ─── prod 안전장치 / fail-closed ──────────────────────────────

    @Test
    fun `prod + test-mode=true 는 INVALID_INPUT(무료지급 사고 차단)`() {
        whenever(environment.activeProfiles).thenReturn(arrayOf("prod"))
        val ex = assertThrows<BusinessException> { controller(testMode = true).verify(req()) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        // grant 절대 호출되면 안 됨
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `live 인데 verifier Disabled = config 오류 → INVALID_INPUT (fail-closed)`() {
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Disabled)
        val ex = assertThrows<BusinessException> { controller(testMode = false).verify(req()) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    // ─── verifier 결과 → HTTP status ─────────────────────────────

    @Test
    fun `verifier InvalidToken 은 400 + grant 미발생`() {
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.invalidToken("bad"))
        val res = controller(testMode = false).verify(req())
        assertEquals(400, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `verifier UserMismatch 는 401 + grant 미발생`() {
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.userMismatch("user-1", "user-2"))
        val res = controller(testMode = false).verify(req())
        assertEquals(401, res.statusCode.value())
        verify(entitlementService, never()).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `verifier Ok 면 grant 호출 + 200`() {
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.GRANTED)
        val res = controller(testMode = false).verify(req())
        assertEquals(200, res.statusCode.value())
        assertTrue(res.body!!.granted)
        assertEquals("free_placement", res.body!!.entitlementKey)
        verify(entitlementService).grant(any(), any(), any(), anyOrNull())
    }

    @Test
    fun `R4-ENT-01 — 이미 보유(ALREADY_PRESENT)는 기존 멱등 계약 유지 — 200 + granted=false`() {
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.ALREADY_PRESENT)
        val res = controller(testMode = false).verify(req())
        assertEquals(200, res.statusCode.value())
        assertEquals(false, res.body!!.granted)
    }

    @Test
    fun `V36 — grant TxRefConflict throw 는 예외 전파(5xx) + tx 밖 conflict audit publish`() {
        // V36 재설계: tx_ref 충돌은 서비스가 자신의 tx 안에서 throw (entitlement/원장 함께 롤백).
        // 200 으로 삼키면 스토어 결제 완료 + entitlement 미부여가 영구 은폐된다 — 예외 전파 →
        // 500 → 클라(usePayment) 재시도 + 운영 surface. conflict audit 은 서비스 tx 롤백과 함께
        // 유실되므로 컨트롤러가 tx 밖에서 publish 해야 한다 (fallbackExecution=true 수신 경로).
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull()))
            .thenThrow(EntitlementTxRefConflictException("user-1", "free_placement", "ptoken-1", "PURCHASE"))
        assertThrows<EntitlementTxRefConflictException> { controller(testMode = false).verify(req()) }
        verify(auditService).publish(
            eq("user-1"),
            eq("ENTITLEMENT_GRANT_TXREF_CONFLICT"),
            eq("Entitlement"),
            eq("free_placement"),
            anyOrNull(),
        )
    }

    @Test
    fun `V36 — 환불된 토큰(REVOKED terminal)의 재검증은 200 + granted=false (권리 미생성 멱등)`() {
        // REVOKE 가 이미 처리된 purchaseToken 의 verify 재호출 — 권리를 만들지 않고 멱등 응답.
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull())).thenReturn(GrantResult.REVOKED)
        val res = controller(testMode = false).verify(req())
        assertEquals(200, res.statusCode.value())
        assertEquals(false, res.body!!.granted)
    }

    @Test
    fun `BE-01 — verify 성공 + grant 예외는 전파(5xx 재시도 계약, 200 삼키기 금지)`() {
        // 컨트롤러 @Transactional 제거 후에도 grant 실패는 그대로 전파돼 5xx 가 되어야 한다.
        // 200 으로 삼키면 스토어 결제 완료 + entitlement 미부여가 영구 은폐됨 (클라 재시도 차단).
        whenever(verifier.verifyToken(any(), any(), any())).thenReturn(VerificationResult.Ok)
        whenever(entitlementService.grant(any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("db down"))
        assertThrows<RuntimeException> { controller(testMode = false).verify(req()) }
    }
}
