package com.terraworld.api.item

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.Rarity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.ShopApi
import io.terraworld.api.model.ItemListResponse
import io.terraworld.api.model.ItemResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * ShopApi (2 endpoints — getItem, listItems) implement. spec PR #12 의
 * tag 분리 (Shop ↔ Purchase) 결과 ShopApi 는 카탈로그만 책임.
 *
 * local ItemResponse 데이터 클래스는 generated 와 동등하므로 제거하고
 * generated 직접 사용. 단 generated 는 priceType/rarity/layout 을 enum
 * 으로 강제 — domain enum (PriceType/Rarity/ItemLayout) 의 .name 을
 * forValue 로 변환.
 */
@Tag(name = "Shop", description = "상점 아이템 API (목록 / 상세)")
@RestController
@RequestMapping("/api/v1")
class ItemController(
    private val itemRepository: ItemRepository,
) : ShopApi {
    @Operation(summary = "아이템 목록 조회")
    override fun listItems(
        categoryId: Long?,
        rarity: String?,
        layout: String?,
    ): ResponseEntity<ItemListResponse> {
        val items =
            when {
                categoryId != null -> itemRepository.findAllByIsActiveTrueAndCategoryId(categoryId)
                rarity != null -> itemRepository.findAllByIsActiveTrueAndRarity(Rarity.valueOf(rarity))
                layout != null -> itemRepository.findAllByIsActiveTrueAndLayout(ItemLayout.valueOf(layout))
                else -> itemRepository.findAllByIsActiveTrue()
            }
        return ResponseEntity.ok(ItemListResponse(items = items.map { it.toApi() }))
    }

    @Operation(summary = "아이템 상세 조회")
    override fun getItem(itemId: Long): ResponseEntity<ItemResponse> {
        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }
        return ResponseEntity.ok(item.toApi())
    }

    private fun Item.toApi() =
        ItemResponse(
            id = id,
            slug = slug,
            name = name,
            description = description,
            categoryId = category?.id,
            categoryName = category?.name,
            priceType = ItemResponse.PriceType.forValue(priceType.name),
            priceAmount = priceAmount,
            tokenPrice = tokenPrice,
            rarity = ItemResponse.Rarity.forValue(rarity.name),
            assetUrl = assetUrl,
            layout = ItemResponse.Layout.forValue(layout.name),
            isAnimated = isAnimated,
        )
}
