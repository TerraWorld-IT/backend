package com.terraworld.api.notification

import com.terraworld.api.notification.MarketingSendPolicy.SendDecision
import org.junit.jupiter.api.Test
import java.time.LocalTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P3-1 (정보통신망법 제50조): 마케팅 발송 정책 — 동의 게이트 + 야간 전송 제한.
 */
class MarketingSendPolicyTest {
    private val policy = MarketingSendPolicy()

    @Test
    fun `미동의면 BLOCKED_NO_CONSENT (시간 무관)`() {
        assertEquals(SendDecision.BLOCKED_NO_CONSENT, policy.decide(marketingConsent = false, at = LocalTime.NOON))
        assertEquals(SendDecision.BLOCKED_NO_CONSENT, policy.decide(marketingConsent = false, at = LocalTime.of(22, 0)))
        assertFalse(policy.canSend(marketingConsent = false, at = LocalTime.NOON))
    }

    @Test
    fun `동의 + 주간(08~21)은 ALLOWED`() {
        assertEquals(SendDecision.ALLOWED, policy.decide(marketingConsent = true, at = LocalTime.of(8, 0)))
        assertEquals(SendDecision.ALLOWED, policy.decide(marketingConsent = true, at = LocalTime.NOON))
        assertEquals(SendDecision.ALLOWED, policy.decide(marketingConsent = true, at = LocalTime.of(20, 59)))
        assertTrue(policy.canSend(marketingConsent = true, at = LocalTime.of(15, 30)))
    }

    @Test
    fun `동의해도 야간(21~익일08)은 BLOCKED_NIGHT`() {
        assertEquals(SendDecision.BLOCKED_NIGHT, policy.decide(marketingConsent = true, at = LocalTime.of(21, 0)))
        assertEquals(SendDecision.BLOCKED_NIGHT, policy.decide(marketingConsent = true, at = LocalTime.of(23, 30)))
        assertEquals(SendDecision.BLOCKED_NIGHT, policy.decide(marketingConsent = true, at = LocalTime.of(0, 0)))
        assertEquals(SendDecision.BLOCKED_NIGHT, policy.decide(marketingConsent = true, at = LocalTime.of(7, 59)))
    }

    @Test
    fun `야간 경계 — 21시00분 포함, 08시00분 미포함`() {
        assertTrue(policy.isNightTime(LocalTime.of(21, 0))) // 야간 시작 포함
        assertFalse(policy.isNightTime(LocalTime.of(20, 59))) // 직전은 주간
        assertFalse(policy.isNightTime(LocalTime.of(8, 0))) // 야간 종료 — 주간
        assertTrue(policy.isNightTime(LocalTime.of(7, 59))) // 직전은 야간
    }
}
