package com.terraworld.api.purchase

import com.terraworld.api.purchase.dto.PurchaseRequest
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.PurchaseResponse
import io.terraworld.api.model.PurchasedItemInfo
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PurchaseService(
    private val userRepository: UserRepository,
    private val itemRepository: ItemRepository,
    private val userItemRepository: UserItemRepository,
    private val walletBuilder: WalletBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 아이템 구매 시 wallet_transactions 원장 기록 (재화 차감 = 음수)
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    // 낙서장 P1 read-cutover(리뷰 A#1 CRIT): 구매 debit 을 신 substrate 에도 반영 (지갑 read SoT 정합)
    private val currencyService: com.terraworld.api.currency.CurrencyService,
) {
    /**
     * 토큰 결제 화폐 해석 (단일 SoT [CurrencyCode.elementTokenForCategory] 참조).
     * 원소 토큰이 없는 카테고리(커스텀 5+)는 토큰 결제 불가 → config 오류 fail-fast
     * (silent COIN default 금지: 토큰가 아이템이 코인으로 잘못 청구되는 것을 차단).
     */
    private fun requireTokenCurrency(category: com.terraworld.domain.category.Category): String =
        com.terraworld.domain.currency.CurrencyCode
            .elementTokenForCategory(category.id)
            ?: throw BusinessException(
                ErrorCode.INTERNAL_ERROR,
                "토큰 결제 불가 카테고리입니다 (원소 토큰 미지정: categoryId=${category.id})",
            )

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

        // 아프젝 v2 (V39): purchasable=false 아이템(정령 등 지급 전용)은 목록에는 보이지만 구매 불가 — 409.
        if (!item.purchasable) {
            throw BusinessException(ErrorCode.ITEM_NOT_PURCHASABLE)
        }

        if (userItemRepository.existsByUserIdAndItemId(userId, item.id)) {
            throw BusinessException(ErrorCode.ALREADY_OWNED)
        }

        // 낙서장 P1: 재화 차감은 신 substrate(CurrencyService) 단일 SoT — debit 이 잔액 체크(INSUFFICIENT_FUNDS)
        // + 원장(appendNormalized) 을 원자적으로 수행. 구 필드/구 원장 dual-write 제거(V32 컬럼 드롭).
        val purchaseReason = com.terraworld.api.wallet.WalletTransactionService.REASON_PURCHASE
        val coin = com.terraworld.domain.currency.CurrencyCode.COIN
        val ruby = com.terraworld.domain.currency.CurrencyCode.RUBY
        when (item.priceType) {
            PriceType.BASIC ->
                currencyService.debit(userId, coin, item.priceAmount.toLong(), purchaseReason, "ITEM", item.id.toString())
            PriceType.SPECIAL ->
                currencyService.debit(userId, ruby, item.priceAmount.toLong(), purchaseReason, "ITEM", item.id.toString())
            PriceType.MIXED -> {
                // MIXED 는 tokenPrice + category 필수 (config 오류 fail-fast).
                val tokenPrice =
                    item.tokenPrice
                        ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "MIXED 아이템 가격 설정 오류 (tokenPrice 누락)")
                val category =
                    item.category
                        ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "MIXED 아이템 가격 설정 오류 (category 누락)")
                currencyService.debit(userId, coin, item.priceAmount.toLong(), purchaseReason, "ITEM", item.id.toString())
                currencyService.debit(userId, requireTokenCurrency(category), tokenPrice.toLong(), purchaseReason, "ITEM", item.id.toString())
            }
            PriceType.TOKEN -> {
                // TOKEN 은 category 필수 (config 오류 fail-fast). category 부재 시 무료 지급 방지.
                val category =
                    item.category
                        ?: throw BusinessException(ErrorCode.INTERNAL_ERROR, "TOKEN 아이템 가격 설정 오류 (category 누락)")
                currencyService.debit(userId, requireTokenCurrency(category), item.priceAmount.toLong(), purchaseReason, "ITEM", item.id.toString())
            }
        }
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
            updatedCurrency = walletBuilder.build(userId, user),
            ownedItems = ownedSlugs,
        )
    }
}
