package com.terraworld.api.purchase.dto

import com.terraworld.api.user.dto.CurrencyResponse
import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "구매 요청")
data class PurchaseRequest(
    @Schema(example = "1", description = "아이템 ID")
    val itemId: Long,
)

@Schema(description = "구매 응답")
data class PurchaseResponse(
    val purchasedItem: PurchasedItemInfo,
    val updatedCurrency: CurrencyResponse,
    val ownedItems: List<String>,
)

data class PurchasedItemInfo(
    val id: Long,
    val slug: String?,
    val name: String,
)
