package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.*
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.CurrencyResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.math.floor

@Service
class ExchangeService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val exchangeRateRepository: TokenExchangeRateRepository,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
) {
    companion object {
        const val SPECIAL_TO_BASIC_RATE = 2.0
    }

    @Transactional
    fun specialToBasic(
        userId: String,
        request: SpecialToBasicRequest,
    ): ExchangeResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        if (user.specialCoin < request.amount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

        val received = (request.amount * SPECIAL_TO_BASIC_RATE).toInt()
        user.specialCoin -= request.amount
        user.basicCoin += received
        userRepository.save(user)

        auditService.publish(
            userId = userId,
            action = "EXCHANGE_S2B",
            resourceType = "Wallet",
            payload =
                mapOf(
                    "specialCoinSpent" to request.amount,
                    "basicCoinReceived" to received,
                    "rate" to SPECIAL_TO_BASIC_RATE,
                ),
        )

        return ExchangeResponse(
            exchanged = ExchangeInfo("SPECIAL_COIN", request.amount, "BASIC_COIN", received, SPECIAL_TO_BASIC_RATE),
            updatedCurrency = buildCurrency(userId, user),
        )
    }

    @Transactional
    fun exchangeTokens(
        userId: String,
        request: TokenExchangeRequest,
    ): ExchangeResponse {
        if (request.fromCategoryId == request.toCategoryId) {
            throw BusinessException(ErrorCode.SAME_CATEGORY_EXCHANGE)
        }

        val rate =
            exchangeRateRepository
                .findByFromCategoryIdAndToCategoryIdAndIsActiveTrue(request.fromCategoryId, request.toCategoryId)
                .orElseThrow { BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND) }

        val fromToken =
            userTokenRepository
                .findByUserIdAndCategoryId(userId, request.fromCategoryId)
                .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }

        if (fromToken.amount < request.amount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

        val received = floor(request.amount / rate.rate.toDouble()).toInt()
        fromToken.amount -= request.amount
        userTokenRepository.save(fromToken)

        val toToken =
            userTokenRepository
                .findByUserIdAndCategoryId(userId, request.toCategoryId)
                .orElseThrow()
        toToken.amount += received
        userTokenRepository.save(toToken)

        val user = userRepository.findById(userId).orElseThrow()

        auditService.publish(
            userId = userId,
            action = "EXCHANGE_TOKEN",
            resourceType = "Wallet",
            payload =
                mapOf(
                    "fromCategoryId" to request.fromCategoryId,
                    "toCategoryId" to request.toCategoryId,
                    "amountSpent" to request.amount,
                    "amountReceived" to received,
                    "rate" to rate.rate,
                ),
        )

        return ExchangeResponse(
            exchanged =
                ExchangeInfo(
                    fromToken.category.name,
                    request.amount,
                    toToken.category.name,
                    received,
                    1.0 / rate.rate,
                ),
            updatedCurrency = buildCurrency(userId, user),
        )
    }

    private fun buildCurrency(
        userId: String,
        user: com.terraworld.domain.user.User,
    ): CurrencyResponse = currencyBuilder.build(userId, user)
}
