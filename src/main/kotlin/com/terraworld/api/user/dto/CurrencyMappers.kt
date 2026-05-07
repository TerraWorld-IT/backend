package com.terraworld.api.user.dto

import io.terraworld.api.model.CategoryTokenAmount as ApiCategoryTokenAmount
import io.terraworld.api.model.CurrencyResponse as ApiCurrencyResponse

/**
 * local CurrencyResponse / CategoryTokenAmount 를 generated DTO 로 변환하는
 * 공용 extension. UserController / RewardController / PurchaseController /
 * RecordController / ExchangeController 가 모두 동일 boilerplate 를 갖던 것을
 * 한 곳으로 추출했다.
 *
 * 후속 cleanup (별도 PR) 으로 service 가 generated DTO 를 직접 반환하도록
 * 마이그레이션하면 본 mapper 자체도 제거 가능 — 단 기존 service test 들이
 * local DTO 를 검증하므로 ripple 분석 후 진행.
 */
fun CurrencyResponse.toApi(): ApiCurrencyResponse =
    ApiCurrencyResponse(
        basicCoins = basicCoins,
        specialCoins = specialCoins,
        walkTokens = walkTokens,
        readTokens = readTokens,
        runTokens = runTokens,
        drawTokens = drawTokens,
        categoryTokens = categoryTokens.map { it.toApi() },
    )

fun CategoryTokenAmount.toApi(): ApiCategoryTokenAmount =
    ApiCategoryTokenAmount(
        categoryId = categoryId,
        categoryName = categoryName,
        amount = amount,
    )
