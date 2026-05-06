package com.terraworld.api.reward

import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Reward", description = "광고 보상 API")
@RestController
@RequestMapping("/api/v1/rewards")
class RewardController(
    private val rewardService: RewardService,
) {
    @Operation(
        summary = "광고 시청 보상 수령",
        description = "하루 최대 5회까지 광고 시청 시 스페셜 코인 1개 지급.",
    )
    @PostMapping("/ad")
    fun claimAdReward(): AdRewardResponsePayload = rewardService.claimAdReward(SecurityUtil.getCurrentUserId())
}

data class AdRewardResponsePayload(
    val reward: AdRewardPayload,
    val dailyWatchCount: Int,
    val remainingToday: Int,
    val updatedCurrency: CurrencyResponse,
)

data class AdRewardPayload(
    val specialCoins: Int,
)
