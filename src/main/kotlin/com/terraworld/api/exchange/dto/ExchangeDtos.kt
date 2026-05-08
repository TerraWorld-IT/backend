package com.terraworld.api.exchange.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.terraworld.api.model.CurrencyResponse
import jakarta.validation.constraints.Positive

@Schema(description = "스페셜→기본 코인 환전 요청")
data class SpecialToBasicRequest(
    @field:Positive
    @Schema(example = "5")
    val amount: Int,
)

@Schema(description = "토큰 교환 요청")
data class TokenExchangeRequest(
    @Schema(example = "1", description = "보낼 카테고리 ID")
    val fromCategoryId: Long,
    @Schema(example = "2", description = "받을 카테고리 ID")
    val toCategoryId: Long,
    @field:Positive
    @Schema(example = "10", description = "교환 수량")
    val amount: Int,
)

@Schema(description = "환전 응답")
data class ExchangeResponse(
    val exchanged: ExchangeInfo,
    val updatedCurrency: CurrencyResponse,
)

data class ExchangeInfo(
    val fromType: String,
    val fromAmount: Int,
    val toType: String,
    val toAmount: Int,
    val rate: Double,
)
