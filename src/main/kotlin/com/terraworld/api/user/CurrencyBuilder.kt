package com.terraworld.api.user

import com.terraworld.api.user.dto.CategoryTokenAmount
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.stereotype.Component

/**
 * Centralizes the construction of [CurrencyResponse]. Replaces the previous pattern of
 * hand-rolling `tokenMap[1L]`, `tokenMap[2L]`... in every service — that hardcoded the
 * 4 system categories and made custom categories invisible. This builder fills the
 * legacy 4 fields (walkTokens / readTokens / runTokens / drawTokens) for back-compat
 * and simultaneously populates [CurrencyResponse.categoryTokens] with EVERY token
 * row (system + custom).
 */
@Component
class CurrencyBuilder(
    private val userTokenRepository: UserTokenRepository,
) {
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
            walkTokens = (tokenMap[1L]?.amount ?: 0).toDouble(),
            readTokens = (tokenMap[2L]?.amount ?: 0).toDouble(),
            runTokens = (tokenMap[3L]?.amount ?: 0).toDouble(),
            drawTokens = (tokenMap[4L]?.amount ?: 0).toDouble(),
            categoryTokens = all,
        )
    }
}
