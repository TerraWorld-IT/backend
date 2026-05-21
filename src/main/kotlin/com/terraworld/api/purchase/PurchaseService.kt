package com.terraworld.api.purchase

import com.terraworld.api.purchase.dto.PurchaseRequest
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.PurchaseResponse
import io.terraworld.api.model.PurchasedItemInfo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PurchaseService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val itemRepository: ItemRepository,
    private val userItemRepository: UserItemRepository,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 아이템 구매 시 wallet_transactions 원장 기록 (재화 차감 = 음수)
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
) {
    @Transactional
    fun purchase(
        userId: String,
        request: PurchaseRequest,
    ): PurchaseResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val item =
            itemRepository
                .findById(request.itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }

        // code-review CDX-001 (2026-05-21): 비활성(판매 중지) 아이템 구매 차단.
        // N5 admin 이 item.isActive 를 토글할 수 있으므로 구매 경로에서 필터 필수.
        if (!item.isActive) {
            throw BusinessException(ErrorCode.ITEM_NOT_FOUND, "판매 중지된 아이템입니다")
        }

        if (userItemRepository.existsByUserIdAndItemId(userId, item.id)) {
            throw BusinessException(ErrorCode.ALREADY_OWNED)
        }

        // 재화 차감 + N2 wallet ledger (차감 = 음수 amount)
        val basicWallet = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_BASIC_COIN
        val specialWallet = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_SPECIAL_COIN
        val tokenWallet = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_CATEGORY_TOKEN
        val purchaseReason = com.terraworld.api.wallet.WalletTransactionService.REASON_PURCHASE
        when (item.priceType) {
            PriceType.BASIC -> {
                if (user.basicCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                user.basicCoin -= item.priceAmount
                walletTransactionService.append(
                    user,
                    basicWallet,
                    -item.priceAmount.toLong(),
                    user.basicCoin,
                    purchaseReason,
                    referenceId = item.id,
                )
            }
            PriceType.SPECIAL -> {
                if (user.specialCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                user.specialCoin -= item.priceAmount
                walletTransactionService.append(
                    user,
                    specialWallet,
                    -item.priceAmount.toLong(),
                    user.specialCoin,
                    purchaseReason,
                    referenceId = item.id,
                )
            }
            PriceType.MIXED -> {
                // Codex audit Q2 (구현 계획서 v4): MIXED 아이템은 tokenPrice + category 필수.
                // 둘 중 하나라도 누락이면 basicCoin 만 차감하고 토큰 ledger 가 누락되는 결함 →
                // 진입 시 fail-fast (config 오류).
                val tokenPrice =
                    item.tokenPrice
                        ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "MIXED 아이템 가격 설정 오류 (tokenPrice 누락)")
                val category =
                    item.category
                        ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "MIXED 아이템 가격 설정 오류 (category 누락)")

                if (user.basicCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                val token =
                    userTokenRepository
                        .findByUserIdAndCategoryId(userId, category.id)
                        .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }
                if (token.amount < tokenPrice) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)

                user.basicCoin -= item.priceAmount
                walletTransactionService.append(
                    user,
                    basicWallet,
                    -item.priceAmount.toLong(),
                    user.basicCoin,
                    purchaseReason,
                    referenceId = item.id,
                )
                token.amount -= tokenPrice
                userTokenRepository.save(token)
                walletTransactionService.append(
                    user,
                    tokenWallet,
                    -tokenPrice.toLong(),
                    token.amount.toLong(),
                    purchaseReason,
                    category = category,
                    referenceId = item.id,
                )
            }
            PriceType.TOKEN -> {
                val category = item.category
                if (category != null) {
                    val token =
                        userTokenRepository
                            .findByUserIdAndCategoryId(userId, category.id)
                            .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }
                    if (token.amount < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                    token.amount -= item.priceAmount
                    userTokenRepository.save(token)
                    walletTransactionService.append(
                        user,
                        tokenWallet,
                        -item.priceAmount.toLong(),
                        token.amount.toLong(),
                        purchaseReason,
                        category = category,
                        referenceId = item.id,
                    )
                }
            }
        }
        userRepository.save(user)
        userItemRepository.save(UserItem(user = user, item = item))

        val ownedSlugs = userItemRepository.findAllByUserId(userId).mapNotNull { it.item.slug }

        auditService.publish(
            userId = userId,
            action = "PURCHASE_ITEM",
            resourceType = "Item",
            resourceId = item.id.toString(),
            payload =
                mapOf(
                    "itemSlug" to item.slug,
                    "itemName" to item.name,
                    "priceType" to item.priceType.name,
                    "priceAmount" to item.priceAmount,
                ),
        )

        return PurchaseResponse(
            purchasedItem = PurchasedItemInfo(id = item.id, slug = item.slug, name = item.name),
            updatedCurrency = currencyBuilder.build(userId, user),
            ownedItems = ownedSlugs,
        )
    }
}
