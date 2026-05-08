package com.terraworld.api.user.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.terraworld.api.model.CurrencyResponse

@Schema(description = "사용자 entitlement (유료/이벤트 해금 권리)")
data class EntitlementsResponse(
    val freePlacement: Boolean,
    val premiumThemes: Boolean,
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
    val userId: String,
    val email: String,
    val nickname: String,
    val role: String,
    val currency: CurrencyResponse,
    val progress: ProgressResponse,
    val ownedItems: List<String>,
    val placedItems: List<PlacedItemResponse>,
    val entitlements: EntitlementsResponse = EntitlementsResponse(false, false),
)
