package com.terraworld.api.user.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "재화 정보")
data class CurrencyResponse(
    val basicCoins: Double,
    val specialCoins: Double,
    val walkTokens: Double,
    val readTokens: Double,
    val runTokens: Double,
    val drawTokens: Double,
)

@Schema(description = "레벨 진행도")
data class ProgressResponse(
    val level: Int,
    val experience: Long,
    val experienceToNext: Long,
)

@Schema(description = "배치 아이템")
data class PlacedItemResponse(
    val itemId: Long,
    val itemSlug: String?,
    val slotId: Int?,
)

@Schema(description = "유저 전체 데이터")
data class UserMeResponse(
    val userId: Long,
    val email: String,
    val nickname: String,
    val currency: CurrencyResponse,
    val progress: ProgressResponse,
    val ownedItems: List<String>,
    val placedItems: List<PlacedItemResponse>,
)
