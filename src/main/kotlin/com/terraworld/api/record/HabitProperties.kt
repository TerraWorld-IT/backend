package com.terraworld.api.record

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 습관 보상/요청 설정 (아프젝 v2 §R2).
 * - `habit.reward.solo-sparkle`: solo 완주(또는 상대 중단 시) 반짝이 — 기본 100
 * - `habit.reward.partner-sparkle`: 친구 습관 완주 시 양측 각각 — 기본 200
 * - `habit.request-expiry-days`: 친구 시작/연장 요청 만료 일수 — 기본 7
 */
@ConfigurationProperties(prefix = "habit")
data class HabitProperties(
    val reward: Reward = Reward(),
    val requestExpiryDays: Long = 7,
) {
    data class Reward(
        val soloSparkle: Long = 100,
        val partnerSparkle: Long = 200,
    )

    init {
        require(reward.soloSparkle >= 0 && reward.partnerSparkle >= 0) { "habit.reward.* 는 음수 불가" }
        require(requestExpiryDays > 0) { "habit.request-expiry-days 는 1 이상" }
    }
}
