package com.terraworld.api.social

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 초대 수락 보상 설정 (아프젝 v2 §3). 초대자/수락자 각각 지급하는 루비 — 종전 양측 5 상수를 대체.
 * `invite.reward.inviter-ruby` / `invite.reward.invitee-ruby`.
 */
@ConfigurationProperties(prefix = "invite.reward")
data class InviteRewardProperties(
    val inviterRuby: Int = 30,
    val inviteeRuby: Int = 10,
) {
    init {
        require(inviterRuby >= 0 && inviteeRuby >= 0) { "invite.reward.* 는 음수 불가" }
    }
}
