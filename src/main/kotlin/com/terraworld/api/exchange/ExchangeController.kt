package com.terraworld.api.exchange

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
import io.terraworld.api.model.ExchangeResponse as ApiExchangeResponse
import io.terraworld.api.model.SpecialToBasicRequest as ApiSpecialToBasicRequest
import io.terraworld.api.model.TokenExchangeRequest as ApiTokenExchangeRequest
import io.terraworld.api.model.TokenToBasicRequest as ApiTokenToBasicRequest

/**
 * ExchangeApi (2 endpoints) implement. ARCH-008-phase-8 후: ExchangeService 가
 * generated [ApiExchangeResponse] 직접 반환 — controller 매퍼 제거. local
 * SpecialToBasicRequest / TokenExchangeRequest 만 잔존 (request 변환은 별도 phase).
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
        val response =
            exchangeService.specialToBasic(
                userId = SecurityUtil.getCurrentUserId(),
                request = LocalSpecialToBasicRequest(amount = specialToBasicRequest.amount),
            )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "토큰 교환", description = "카테고리 간 토큰 교환 (비율은 DB 설정 기준)")
    override fun exchangeTokens(
        @Valid tokenExchangeRequest: ApiTokenExchangeRequest,
    ): ResponseEntity<ApiExchangeResponse> {
        val response =
            exchangeService.exchangeTokens(
                userId = SecurityUtil.getCurrentUserId(),
                request =
                    LocalTokenExchangeRequest(
                        fromCategoryId = tokenExchangeRequest.fromCategoryId,
                        toCategoryId = tokenExchangeRequest.toCategoryId,
                        amount = tokenExchangeRequest.amount,
                    ),
            )
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "토큰 → 이슬 교환", description = "N15: 카테고리 토큰을 기본 코인(이슬)으로 교환")
    override fun exchangeTokenToBasic(
        @Valid tokenToBasicRequest: ApiTokenToBasicRequest,
    ): ResponseEntity<ApiExchangeResponse> {
        val response =
            exchangeService.tokenToBasic(
                userId = SecurityUtil.getCurrentUserId(),
                fromCategoryId = tokenToBasicRequest.fromCategoryId,
                amount = tokenToBasicRequest.amount,
            )
        return ResponseEntity.ok(response)
    }
}
