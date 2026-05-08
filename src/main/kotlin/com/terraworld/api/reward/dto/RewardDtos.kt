package com.terraworld.api.reward.dto

import com.terraworld.api.user.dto.CurrencyResponse

/**
 * RewardService.claimAdReward 의 반환 타입.
 *
 * ARCH-002: 이전엔 RewardController.kt 하단에 module-level 로 거주 — service
 * 시그니처용 DTO 가 controller 파일에 있는 계층 역전. service 패키지 옆
 * `dto/` 로 이동.
 *
 * 후속 슬라이스에서 service 가 generated `io.terraworld.api.model.AdRewardResponse`
 * 를 직접 반환하도록 마이그레이션 후 제거 예정.
 */
data class AdRewardResponsePayload(
    val reward: AdRewardPayload,
    val dailyWatchCount: Int,
    val remainingToday: Int,
    val updatedCurrency: CurrencyResponse,
)

data class AdRewardPayload(
    val specialCoins: Int,
)
