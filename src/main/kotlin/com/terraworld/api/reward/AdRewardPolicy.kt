package com.terraworld.api.reward

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.Locale

enum class AdRewardMode(
    val configValue: String,
) {
    LEGACY("legacy"),
    SHADOW("shadow"),
    AUTHORITATIVE("authoritative"),
    ;

    companion object {
        fun parse(value: String): AdRewardMode =
            entries.firstOrNull { it.configValue == value.trim().lowercase(Locale.ROOT) }
                ?: throw IllegalArgumentException(
                    "reward.ad.mode 는 legacy, shadow, authoritative 중 하나여야 합니다: $value",
                )
    }
}

@Component
class AdRewardPolicy(
    @Value("\${reward.ad.mode:legacy}") configuredMode: String = AdRewardMode.LEGACY.configValue,
) {
    val mode: AdRewardMode = AdRewardMode.parse(configuredMode)
}
