package com.terraworld.api.growth

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 키우기 사이클 설정 (아프젝 v2 §R3).
 * - `growth.lost-after-days`: LOST 판정 시작 간격 — `DAYS.between(lastProgressDate, today) >= N` 이면 LOST (기본 2 =
 *   lastProgressDate + 1일의 KST 종료까지 미기록 시 D+2 00:00 KST 부터).
 * - `growth.revive-ruby-cost`: 루비 되살리기 비용 (기본 10)
 * - `growth.goal`: 사이클 목표 스탬프 수 (기본 30) — 단계 시드(1/10/20)와 별개
 */
@ConfigurationProperties(prefix = "growth")
data class GrowthProperties(
    val lostAfterDays: Long = 2,
    val reviveRubyCost: Long = 10,
    val goal: Int = 30,
) {
    init {
        require(lostAfterDays >= 1) { "growth.lost-after-days 는 1 이상" }
        require(reviveRubyCost >= 0) { "growth.revive-ruby-cost 는 음수 불가" }
        require(goal >= 1) { "growth.goal 은 1 이상" }
    }
}
