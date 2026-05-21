package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.SpecialToBasicRequest
import com.terraworld.api.exchange.dto.TokenExchangeRequest
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.CategoryRepository
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
    // N2 (구현 계획서 v4, Codex audit Q6): 환전 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    // N15 (Codex audit LOW): token→basic 시 카테고리 존재 검증 (404 정확도)
    private val categoryRepository: CategoryRepository,
) {
    companion object {
        const val SPECIAL_TO_BASIC_RATE = 2.0

        // N15 (구현 계획서 v4): 카테고리 토큰 → 이슬(기본 코인) 환산 비율.
        // 권장 default 1 토큰 = 1 이슬. Phase 5 KPI 후 또는 admin 으로 조정 가능.
        const val TOKEN_TO_BASIC_RATE = 1.0
    }

    /**
     * N15 (구현 계획서 v4): 카테고리 토큰 → 기본 코인(이슬) 교환.
     * 기획서 §4.3 + 모달 E 의 "토큰 ↔ 이슬". `received = amount × TOKEN_TO_BASIC_RATE`.
     */
    @Transactional
    fun tokenToBasic(
        userId: String,
        fromCategoryId: Long,
        amount: Int,
    ): ExchangeResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        // N15 (Codex audit LOW): 카테고리 존재 검증 — spec 의 404 CATEGORY_NOT_FOUND 정합.
        // 미존재 카테고리가 INSUFFICIENT_FUNDS(400) 로 오분류되던 것 정정.
        categoryRepository
            .findById(fromCategoryId)
            .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }

        val fromToken =
            userTokenRepository
                .findByUserIdAndCategoryId(userId, fromCategoryId)
                .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }
        if (fromToken.amount < amount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

        val received = (amount * TOKEN_TO_BASIC_RATE).toInt()
        fromToken.amount -= amount
        userTokenRepository.save(fromToken)
        user.basicCoin += received
        userRepository.save(user)

        // N2 wallet ledger — CATEGORY_TOKEN debit + BASIC_COIN credit
        walletTransactionService.append(
            user,
            com.terraworld.api.wallet.WalletTransactionService.CURRENCY_CATEGORY_TOKEN,
            -amount.toLong(),
            fromToken.amount.toLong(),
            com.terraworld.api.wallet.WalletTransactionService.REASON_EXCHANGE,
            category = fromToken.category,
        )
        walletTransactionService.append(
            user,
            com.terraworld.api.wallet.WalletTransactionService.CURRENCY_BASIC_COIN,
            received.toLong(),
            user.basicCoin,
            com.terraworld.api.wallet.WalletTransactionService.REASON_EXCHANGE,
        )

        auditService.publish(
            userId = userId,
            action = "EXCHANGE_T2B",
            resourceType = "Wallet",
            payload =
                mapOf(
                    "fromCategoryId" to fromCategoryId,
                    "tokenSpent" to amount,
                    "basicCoinReceived" to received,
                    "rate" to TOKEN_TO_BASIC_RATE,
                ),
        )

        return ExchangeResponse(
            exchanged =
                ExchangeInfo(
                    fromType = fromToken.category.name,
                    fromAmount = amount,
                    toType = "BASIC_COIN",
                    toAmount = received,
                    rate = TOKEN_TO_BASIC_RATE,
                ),
            updatedCurrency = currencyBuilder.build(userId, user),
        )
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

        // N2 Q6: wallet ledger — SPECIAL_COIN debit + BASIC_COIN credit
        walletTransactionService.append(
            user,
            com.terraworld.api.wallet.WalletTransactionService.CURRENCY_SPECIAL_COIN,
            -request.amount.toLong(),
            user.specialCoin,
            com.terraworld.api.wallet.WalletTransactionService.REASON_EXCHANGE,
        )
        walletTransactionService.append(
            user,
            com.terraworld.api.wallet.WalletTransactionService.CURRENCY_BASIC_COIN,
            received.toLong(),
            user.basicCoin,
            com.terraworld.api.wallet.WalletTransactionService.REASON_EXCHANGE,
        )

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

        // N2 Q6: wallet ledger — CATEGORY_TOKEN debit (from) + credit (to)
        val tokenWallet = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_CATEGORY_TOKEN
        val exchangeReason = com.terraworld.api.wallet.WalletTransactionService.REASON_EXCHANGE
        walletTransactionService.append(
            user,
            tokenWallet,
            -request.amount.toLong(),
            fromToken.amount.toLong(),
            exchangeReason,
            category = fromToken.category,
        )
        walletTransactionService.append(
            user,
            tokenWallet,
            received.toLong(),
            toToken.amount.toLong(),
            exchangeReason,
            category = toToken.category,
        )

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
