package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.*
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Exchange", description = "재화 교환 API")
@RestController
@RequestMapping("/api/v1/exchange")
class ExchangeController(
    private val exchangeService: ExchangeService,
) {
    @Operation(summary = "스페셜→기본 코인 환전", description = "스페셜 코인 1개 = 기본 코인 2개")
    @PostMapping("/special-to-basic")
    fun specialToBasic(
        @Valid @RequestBody request: SpecialToBasicRequest,
    ): ExchangeResponse = exchangeService.specialToBasic(SecurityUtil.getCurrentUserId(), request)

    @Operation(summary = "토큰 교환", description = "카테고리 간 토큰 교환 (비율은 DB 설정 기준)")
    @PostMapping("/tokens")
    fun exchangeTokens(
        @Valid @RequestBody request: TokenExchangeRequest,
    ): ExchangeResponse = exchangeService.exchangeTokens(SecurityUtil.getCurrentUserId(), request)
}
