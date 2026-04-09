package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.*
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.floor

@Service
class ExchangeService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val exchangeRateRepository: TokenExchangeRateRepository,
) {
    companion object {
        const val SPECIAL_TO_BASIC_RATE = 2.0
    }

    @Transactional
    fun specialToBasic(userId: Long, request: SpecialToBasicRequest): ExchangeResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        if (user.specialCoin < request.amount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

        val received = (request.amount * SPECIAL_TO_BASIC_RATE).toInt()
        user.specialCoin -= request.amount
        user.basicCoin += received
        userRepository.save(user)

        return ExchangeResponse(
            exchanged = ExchangeInfo("SPECIAL_COIN", request.amount, "BASIC_COIN", received, SPECIAL_TO_BASIC_RATE),
            updatedCurrency = buildCurrency(userId, user),
        )
    }

    @Transactional
    fun exchangeTokens(userId: Long, request: TokenExchangeRequest): ExchangeResponse {
        if (request.fromCategoryId == request.toCategoryId) {
            throw BusinessException(ErrorCode.SAME_CATEGORY_EXCHANGE)
        }

        val rate = exchangeRateRepository
            .findByFromCategoryIdAndToCategoryIdAndIsActiveTrue(request.fromCategoryId, request.toCategoryId)
            .orElseThrow { BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND) }

        val fromToken = userTokenRepository.findByUserIdAndCategoryId(userId, request.fromCategoryId)
            .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }

        if (fromToken.amount < request.amount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

        val received = floor(request.amount / rate.rate.toDouble()).toInt()
        fromToken.amount -= request.amount
        userTokenRepository.save(fromToken)

        val toToken = userTokenRepository.findByUserIdAndCategoryId(userId, request.toCategoryId)
            .orElseThrow()
        toToken.amount += received
        userTokenRepository.save(toToken)

        val user = userRepository.findById(userId).orElseThrow()
        return ExchangeResponse(
            exchanged = ExchangeInfo(
                fromToken.category.name, request.amount,
                toToken.category.name, received, 1.0 / rate.rate,
            ),
            updatedCurrency = buildCurrency(userId, user),
        )
    }

    private fun buildCurrency(userId: Long, user: com.terraworld.domain.user.User): CurrencyResponse {
        val tokens = userTokenRepository.findAllByUserId(userId)
        val tokenMap = tokens.associate { it.category.id to it.amount }
        return CurrencyResponse(
            basicCoins = user.basicCoin.toDouble(), specialCoins = user.specialCoin.toDouble(),
            walkTokens = (tokenMap[1L] ?: 0).toDouble(), readTokens = (tokenMap[2L] ?: 0).toDouble(),
            runTokens = (tokenMap[3L] ?: 0).toDouble(), drawTokens = (tokenMap[4L] ?: 0).toDouble(),
        )
    }
}
