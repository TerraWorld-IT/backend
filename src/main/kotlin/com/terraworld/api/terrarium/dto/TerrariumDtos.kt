package com.terraworld.api.terrarium.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "테라리움 상태")
data class TerrariumResponse(
    val terrariumId: Long,
    val background: BackgroundInfo,
    val placedItems: List<PlacedItemDetail>,
    val maxSlots: Int,
    @Schema(description = "현재 진화 단계", required = false)
    val evolutionStage: String = "BOTTLE",
    @Schema(description = "잠금 해제된 진화 단계 목록", required = false)
    val unlockedStages: List<String> = listOf("POT", "BOTTLE"),
    @Schema(description = "시들기 상태", required = false)
    val wilting: WiltingState? = null,
)

@Schema(description = "진화 단계 전환 요청")
data class UpgradeTerrariumRequest(
    @Schema(description = "전환할 단계", example = "PALUDARIUM")
    val targetStage: String,
)

@Schema(description = "진화 단계 전환 응답")
data class UpgradeTerrariumResponse(
    val terrarium: TerrariumResponse,
    val message: String? = null,
)

@Schema(description = "시들기 단계 (daysSinceRecord 기반)")
data class WiltingState(
    @Schema(description = "0=정상, 1=시들기 시작, 2=많이 시듦, 3=위기")
    val stage: Int,
    @Schema(description = "마지막 기록 이후 경과 일수. 한 번도 기록이 없으면 null")
    val daysSinceRecord: Int?,
)

data class BackgroundInfo(
    val id: Long,
    val name: String,
    val assetUrl: String,
)

data class PlacedItemDetail(
    val id: Long,
    val itemId: Long,
    val itemSlug: String?,
    val itemImage: String,
    val itemName: String,
    val itemLayout: String,
    val isAnimated: Boolean,
    val slotId: Int?,
)

@Schema(description = "아이템 배치 요청")
data class PlacementRequest(
    val placedItems: List<PlacementItem>,
)

data class PlacementItem(
    @Schema(example = "1")
    val itemId: Long,
    @Schema(example = "0", description = "슬롯 ID (0,1=후경, 2,4=전경, 3=피규어)")
    val slotId: Int,
)

@Schema(description = "하트 클릭 응답")
data class HeartResponse(
    val reward: Double,
    val updatedBasicCoins: Double,
)
