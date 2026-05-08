package com.terraworld.api.shared.dto

import com.terraworld.api.user.dto.CategoryTokenAmount
import com.terraworld.api.user.dto.CurrencyResponse
import io.terraworld.api.model.CategoryTokenAmount as ApiCategoryTokenAmount
import io.terraworld.api.model.CurrencyResponse as ApiCurrencyResponse

/**
 * local Wallet/Currency 도메인 DTO 를 generated DTO 로 변환하는 공용 extension.
 *
 * cross-cutting concern: 5 controllers (User / Reward / Purchase / Record /
 * Exchange) 가 동일 mapper 보일러플레이트를 사용하므로 단일 위치로 추출.
 *
 * 패키지 정책 (ARCH-001 — 패키지 위치 결정 근거)
 * - 본 mapper 는 cross-domain 이라 특정 도메인 (`user/`) 에 묶이지 않게
 *   `shared/` 로 분리. 향후 wallet 도메인이 별도 추출되면 본 file 만 옮기면
 *   되고 5 controllers 의 `import` 는 그대로 유지된다.
 * - local DTO (`CurrencyResponse`, `CategoryTokenAmount`) 자체는 아직
 *   `com.terraworld.api.user.dto` 에 있다 — 도메인 분리는 별도 PR (ARCH-008
 *   migration plan 의 일부).
 *
 * 후속 cleanup 으로 service 가 generated DTO 를 직접 반환하도록 마이그레이션
 * 하면 본 mapper 도 함께 제거 가능.
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
