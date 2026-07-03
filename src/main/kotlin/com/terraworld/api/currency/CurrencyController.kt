package com.terraworld.api.currency

import com.terraworld.domain.currency.CurrencyRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.CurrencyApi
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import io.terraworld.api.model.Currency as ApiCurrency

/**
 * CurrencyApi (`listCurrencies`) 구현 — 화폐 config 7종 반환 (정적 config, 앱 초기화 시 1회).
 * 잔액은 WalletResponse.balances[](각 응답)로, config(라벨/종류/순서/유료여부)는 본 엔드포인트로 분리.
 *
 * 인증: `/api/v1/currencies` 는 SecurityConfig permitAll 미포함 → 인증 필요(앱 로그인 후 호출).
 * 정적 비민감 config 이나 명시적 결정으로 인증 유지(누출 위험 없음, fall-through 방지 위해 문서화).
 */
@Tag(name = "Currency", description = "화폐 config API")
@RestController
@RequestMapping("/api/v1")
class CurrencyController(
    private val currencyRepository: CurrencyRepository,
) : CurrencyApi {
    private val log = LoggerFactory.getLogger(CurrencyController::class.java)

    @Operation(summary = "화폐 config 목록 조회", description = "7종 화폐(코인/루비/반짝이 + 이슬/햇살/번개/바람) config")
    override fun listCurrencies(): ResponseEntity<List<ApiCurrency>> {
        val currencies =
            currencyRepository.findAllByOrderBySortOrderAsc().map { c ->
                ApiCurrency(
                    code = c.code,
                    kind = mapKindOrFailFast(c.code, c.kind),
                    labelKo = c.labelKo,
                    isPurchasable = c.isPurchasable,
                    sortOrder = c.sortOrder,
                )
            }
        return ResponseEntity.ok(currencies)
    }

    /** ADR-019 spec-drift: 알 수 없는 kind 는 warn 로그(관측) 후 fail-fast(silent drop 금지). */
    private fun mapKindOrFailFast(
        code: String,
        kind: String,
    ): ApiCurrency.Kind =
        runCatching { ApiCurrency.Kind.forValue(kind) }
            .onFailure {
                log.warn(
                    "spec drift: currency code={} unknown kind='{}' (allowed={})",
                    code,
                    kind,
                    ApiCurrency.Kind.entries.map { it.value },
                )
            }.getOrThrow()
}
