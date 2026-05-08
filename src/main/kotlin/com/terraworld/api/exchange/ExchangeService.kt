package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.SpecialToBasicRequest
import com.terraworld.api.exchange.dto.TokenExchangeRequest
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.ExchangeInfo
import io.terraworld.api.model.ExchangeResponse
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

    /**
     * ARCH-008-phase-8: 반환 타입을 generated [ExchangeResponse] 직접 사용.
     * local ExchangeResponse / ExchangeInfo 삭제 + controller 매퍼 제거.
     */
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
            exchanged =
                ExchangeInfo(
                    fromType = "SPECIAL_COIN",
                    fromAmount = request.amount,
                    toType = "BASIC_COIN",
                    toAmount = received,
                    rate = SPECIAL_TO_BASIC_RATE,
                ),
            updatedCurrency = currencyBuilder.build(userId, user),
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
                    fromType = fromToken.category.name,
                    fromAmount = request.amount,
                    toType = toToken.category.name,
                    toAmount = received,
                    rate = 1.0 / rate.rate,
                ),
            updatedCurrency = currencyBuilder.build(userId, user),
        )
    }
}
