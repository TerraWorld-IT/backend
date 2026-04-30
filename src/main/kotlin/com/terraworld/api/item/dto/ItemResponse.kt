package com.terraworld.api.item.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "상점 아이템")
data class ItemResponse(
    val id: Long,
    val slug: String?,
    val name: String,
    val description: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val priceType: String,
    val priceAmount: Int,
    val tokenPrice: Int?,
    val rarity: String,
    val assetUrl: String,
    val layout: String,
    val isAnimated: Boolean,
)
