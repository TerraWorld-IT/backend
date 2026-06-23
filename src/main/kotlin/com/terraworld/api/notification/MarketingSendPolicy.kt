package com.terraworld.api.notification

import com.terraworld.common.time.KstTime
import org.springframework.stereotype.Component
import java.time.LocalTime

/**
 * P3-1 (정보통신망법 제50조): 영리 목적 광고성 정보 전송 정책.
 *
 * 광고성(마케팅) 푸시/이메일 전송 시 반드시 본 정책을 통과해야 한다:
 *  1. **수신 동의 필수** — marketingConsent=false 면 전송 금지 (제50조 제1항).
 *  2. **야간 전송 제한** — KST 21:00 ~ 익일 08:00 사이 광고성 정보 전송 금지
 *     (제50조의8 / 시행령 — 야간 시간대 별도 동의 없으면 전송 불가).
 *
 * ⚠️ 적용 범위: *광고성(마케팅)* 전송에만 적용. wilt/attendance/friend 등 서비스 알림은
 *    광고성 정보가 아니므로 본 정책 비대상(FcmEventListener 경로는 그대로 유지).
 *
 * ⚠️ 동의 값 출처: marketingConsent 는 auth 스키마(better-auth user.marketingConsent, frontend
 *    소유)에 저장됨. 마케팅 발송 caller 가 그 값을 조회해 본 정책에 전달해야 한다(backend public
 *    스키마에는 없음). 발송 경로 신설 시 본 정책을 게이트로 사용.
 */
@Component
class MarketingSendPolicy {
    /**
     * @param marketingConsent 수신자의 마케팅 수신 동의 여부 (auth.user.marketingConsent).
     * @param at 전송 시각(KST). 미지정 시 현재 KST.
     */
    fun decide(
        marketingConsent: Boolean,
        at: LocalTime = KstTime.now().toLocalTime(),
    ): SendDecision =
        when {
            !marketingConsent -> SendDecision.BLOCKED_NO_CONSENT
            isNightTime(at) -> SendDecision.BLOCKED_NIGHT
            else -> SendDecision.ALLOWED
        }

    fun canSend(
        marketingConsent: Boolean,
        at: LocalTime = KstTime.now().toLocalTime(),
    ): Boolean = decide(marketingConsent, at) == SendDecision.ALLOWED

    /** KST 21:00 이상(포함) 또는 08:00 미만 = 야간(광고성 정보 전송 금지). */
    fun isNightTime(at: LocalTime): Boolean = !at.isBefore(NIGHT_START) || at.isBefore(NIGHT_END)

    enum class SendDecision { ALLOWED, BLOCKED_NO_CONSENT, BLOCKED_NIGHT }

    companion object {
        val NIGHT_START: LocalTime = LocalTime.of(21, 0)
        val NIGHT_END: LocalTime = LocalTime.of(8, 0)
    }
}
