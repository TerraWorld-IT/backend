package com.terraworld.api.exchange

import com.terraworld.api.shared.dto.toApi
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.ExchangeApi
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.exchange.dto.SpecialToBasicRequest as LocalSpecialToBasicRequest
import com.terraworld.api.exchange.dto.TokenExchangeRequest as LocalTokenExchangeRequest
import io.terraworld.api.model.ExchangeInfo as ApiExchangeInfo
import io.terraworld.api.model.ExchangeResponse as ApiExchangeResponse
import io.terraworld.api.model.SpecialToBasicRequest as ApiSpecialToBasicRequest
import io.terraworld.api.model.TokenExchangeRequest as ApiTokenExchangeRequest

/**
 * ExchangeApi (2 endpoints) implement. ExchangeService 시그니처 (local
 * DTO) 그대로 두고 controller 에서 generated ↔ local mapper 만 추가 —
 * ExchangeServiceTest 영향 0.
 */
@Tag(name = "Exchange", description = "재화 교환 API")
@RestController
@RequestMapping("/api/v1")
class ExchangeController(
    private val exchangeService: ExchangeService,
) : ExchangeApi {
    @Operation(summary = "스페셜→기본 코인 환전", description = "스페셜 코인 1개 = 기본 코인 2개")
    override fun exchangeSpecialToBasic(
        @Valid specialToBasicRequest: ApiSpecialToBasicRequest,
    ): ResponseEntity<ApiExchangeResponse> {
        val local =
            exchangeService.specialToBasic(
                userId = SecurityUtil.getCurrentUserId(),
                request = LocalSpecialToBasicRequest(amount = specialToBasicRequest.amount),
            )
        return ResponseEntity.ok(
            ApiExchangeResponse(
                exchanged =
                    ApiExchangeInfo(
                        fromType = local.exchanged.fromType,
                        fromAmount = local.exchanged.fromAmount,
                        toType = local.exchanged.toType,
                        toAmount = local.exchanged.toAmount,
                        rate = local.exchanged.rate,
                    ),
                updatedCurrency = local.updatedCurrency.toApi(),
            ),
        )
    }

    @Operation(summary = "토큰 교환", description = "카테고리 간 토큰 교환 (비율은 DB 설정 기준)")
    override fun exchangeTokens(
        @Valid tokenExchangeRequest: ApiTokenExchangeRequest,
    ): ResponseEntity<ApiExchangeResponse> {
        val local =
            exchangeService.exchangeTokens(
                userId = SecurityUtil.getCurrentUserId(),
                request =
                    LocalTokenExchangeRequest(
                        fromCategoryId = tokenExchangeRequest.fromCategoryId,
                        toCategoryId = tokenExchangeRequest.toCategoryId,
                        amount = tokenExchangeRequest.amount,
                    ),
            )
        return ResponseEntity.ok(
            ApiExchangeResponse(
                exchanged =
                    ApiExchangeInfo(
                        fromType = local.exchanged.fromType,
                        fromAmount = local.exchanged.fromAmount,
                        toType = local.exchanged.toType,
                        toAmount = local.exchanged.toAmount,
                        rate = local.exchanged.rate,
                    ),
                updatedCurrency = local.updatedCurrency.toApi(),
            ),
        )
    }
}
