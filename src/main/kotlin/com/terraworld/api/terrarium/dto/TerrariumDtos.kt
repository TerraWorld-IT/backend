package com.terraworld.api.terrarium.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "테라리움 상태")
data class TerrariumResponse(
    val terrariumId: Long,
    val background: BackgroundInfo,
    val placedItems: List<PlacedItemDetail>,
    val maxSlots: Int,
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
