package com.terraworld.api.terrarium

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 테라리움 보상 설정.
 *
 * - `terrarium.heart.daily-cap`: 사용자별 KST 하루 하트 코인 지급 상한. 기본 10회.
 */
@ConfigurationProperties(prefix = "terrarium")
data class TerrariumProperties(
    val heart: Heart = Heart(),
) {
    data class Heart(
        val dailyCap: Long = 10,
    )

    init {
        require(heart.dailyCap >= 0) { "terrarium.heart.daily-cap 은 음수 불가" }
    }
}
