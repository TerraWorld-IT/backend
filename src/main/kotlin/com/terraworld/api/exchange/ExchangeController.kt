package com.terraworld.api.exchange

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.ExchangeApi
import io.terraworld.api.model.ExchangeRateListResponse
import io.terraworld.api.model.ExchangeRequest
import io.terraworld.api.model.ExchangeResult
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ExchangeApi 구현 — 7화폐 directed exchange 실행과 허용 환율표 조회.
 * 낙서장 P1: 구 3 pair-specific 엔드포인트(special-to-basic/tokens/token-to-basic) 대체.
 */
@Tag(name = "Exchange", description = "재화 교환 API")
@RestController
@RequestMapping("/api/v1")
class ExchangeController(
    private val exchangeService: ExchangeService,
) : ExchangeApi {
    @Operation(summary = "재화 교환", description = "허용 directed pair 만 처리 (fee/cap 적용)")
    override fun exchange(
        @Valid exchangeRequest: ExchangeRequest,
    ): ResponseEntity<ExchangeResult> {
        val result =
            exchangeService.exchange(
                userId = SecurityUtil.getCurrentUserId(),
                from = exchangeRequest.from,
                to = exchangeRequest.to,
                amount = exchangeRequest.amount,
            )
        return ResponseEntity.ok(result)
    }

    @Operation(summary = "허용 환전 환율표 조회", description = "환전 실행 전 환율·수수료·일일 한도를 제공")
    override fun getExchangeRates(): ResponseEntity<ExchangeRateListResponse> = ResponseEntity.ok(exchangeService.getExchangeRates())
}
