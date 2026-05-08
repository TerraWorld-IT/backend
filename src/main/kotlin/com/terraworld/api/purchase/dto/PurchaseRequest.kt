package com.terraworld.api.purchase.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "구매 요청")
data class PurchaseRequest(
    @Schema(example = "1", description = "아이템 ID")
    val itemId: Long,
)
