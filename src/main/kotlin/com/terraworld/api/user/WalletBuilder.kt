package com.terraworld.api.user

import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.CategoryTokenAmount
import io.terraworld.api.model.CurrencyResponse
import org.springframework.stereotype.Component

/**
 * Centralizes the construction of [CurrencyResponse]. Replaces the previous pattern of
 * hand-rolling `tokenMap[1L]`, `tokenMap[2L]`... in every service — that hardcoded the
 * 4 system categories and made custom categories invisible. This builder fills the
 * legacy 4 fields (walkTokens / readTokens / runTokens / drawTokens) for back-compat
 * and simultaneously populates [CurrencyResponse.categoryTokens] with EVERY token
 * row (system + custom).
 *
 * ARCH-008 cross-cutting cleanup: 반환 타입을 generated `io.terraworld.api.model.CurrencyResponse`
 * 직접 사용. 5 controllers 의 `local.updatedCurrency.toApi()` 매퍼 + `shared/dto/CurrencyMappers.kt`
 * + `user/dto/{CurrencyResponse,CategoryTokenAmount}` 모두 obsolete.
 */
@Component
class WalletBuilder(
    private val userTokenRepository: UserTokenRepository,
) {
    companion object {
        // code-review: 4개 시스템 카테고리 ID — legacy compat 필드(walk/read/run/draw) 매핑.
        // seed(V1/V22) 의 산책=1 / 독서=2 / 러닝=3 / 낙서=4 와 정합. seed 재번호 시 본 상수만 갱신.
        const val WALK_CATEGORY_ID = 1L
        const val READ_CATEGORY_ID = 2L
        const val RUN_CATEGORY_ID = 3L
        const val DRAW_CATEGORY_ID = 4L
    }

    /** Build from the user object plus a fresh token query. */
    fun build(
        userId: String,
        user: User,
    ): CurrencyResponse {
        val tokens = userTokenRepository.findAllByUserId(userId)
        return build(user, tokens)
    }

    /** Build from already-loaded tokens — preferred when caller has them in hand. */
    fun build(
        user: User,
        tokens: List<UserToken>,
    ): CurrencyResponse {
        val tokenMap = tokens.associateBy { it.category.id }
        val all =
            tokens.map {
                CategoryTokenAmount(
                    categoryId = it.category.id,
                    categoryName = it.category.name,
                    amount = it.amount.toDouble(),
                )
            }
        return CurrencyResponse(
            basicCoins = user.basicCoin.toDouble(),
            specialCoins = user.specialCoin.toDouble(),
            walkTokens = (tokenMap[WALK_CATEGORY_ID]?.amount ?: 0).toDouble(),
            readTokens = (tokenMap[READ_CATEGORY_ID]?.amount ?: 0).toDouble(),
            runTokens = (tokenMap[RUN_CATEGORY_ID]?.amount ?: 0).toDouble(),
            drawTokens = (tokenMap[DRAW_CATEGORY_ID]?.amount ?: 0).toDouble(),
            categoryTokens = all,
        )
    }
}
