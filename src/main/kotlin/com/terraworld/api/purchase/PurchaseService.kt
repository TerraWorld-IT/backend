package com.terraworld.api.purchase

import com.terraworld.api.purchase.dto.*
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.*
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
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

        if (userItemRepository.existsByUserIdAndItemId(userId, item.id)) {
            throw BusinessException(ErrorCode.ALREADY_OWNED)
        }

        // 재화 차감
        when (item.priceType) {
            PriceType.BASIC -> {
                if (user.basicCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                user.basicCoin -= item.priceAmount
            }
            PriceType.SPECIAL -> {
                if (user.specialCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                user.specialCoin -= item.priceAmount
            }
            PriceType.MIXED -> {
                if (user.basicCoin < item.priceAmount) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                user.basicCoin -= item.priceAmount
                val tokenPrice = item.tokenPrice
                val category = item.category
                if (tokenPrice != null && category != null) {
                    val token =
                        userTokenRepository
                            .findByUserIdAndCategoryId(userId, category.id)
                            .orElseThrow { BusinessException(ErrorCode.INSUFFICIENT_FUNDS) }
                    if (token.amount < tokenPrice) throw BusinessException(ErrorCode.INSUFFICIENT_FUNDS)
                    token.amount -= tokenPrice
                    userTokenRepository.save(token)
                }
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
