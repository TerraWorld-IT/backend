package com.terraworld.api.user

import com.terraworld.api.currency.CurrencyService
import com.terraworld.domain.user.User
import io.terraworld.api.model.CurrencyResponse
import org.springframework.stereotype.Component

/**
 * 정규화 [CurrencyResponse] (balances[] — 7종 고정 화폐) 빌더.
 *
 * 낙서장 P1 read-cutover (2026-07-01, code-review 후): 지갑 read SoT 를 구 저장(User.basicCoin/
 * specialCoin + UserToken)에서 **신 substrate(user_currency_balances via CurrencyService)** 로 이전.
 * 이로써 exchange/tier/growth/habit 의 신 substrate 변동 + SPARKLE 이 모든 지갑 응답에 정확히 반영된다
 * (리뷰 Architecture A#2/#3/#5, Codex/Security wallet-SoT-split 해소). 구 컬럼(basicCoin/
 * specialCoin/user_tokens)은 V32 에서 드롭 완료 — 신 substrate(user_currency_balances)가 지갑의 유일 SoT.
 *
 * build() 시그니처는 호출부 호환 위해 유지 — user 인자는 무시하고 userId 로 신 substrate 조회.
 */
@Component
class WalletBuilder(
    private val currencyService: CurrencyService,
) {
    /** Build from userId — 신 substrate 조회 (user 인자는 호환용, 미사용). */
    fun build(
        userId: String,
        @Suppress("UNUSED_PARAMETER") user: User,
    ): CurrencyResponse = currencyService.currencyResponse(userId)
}
