package com.terraworld.api.item

import com.terraworld.api.item.dto.ItemResponse
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.Rarity
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.*

@Tag(name = "Item", description = "상점 아이템 API")
@RestController
@RequestMapping("/api/v1/items")
class ItemController(
    private val itemRepository: ItemRepository,
) {
    @Operation(summary = "아이템 목록 조회")
    @GetMapping
    fun getItems(
        @Parameter(description = "카테고리 ID") @RequestParam(required = false) categoryId: Long?,
        @Parameter(description = "등급 (COMMON, RARE, EPIC)") @RequestParam(required = false) rarity: String?,
        @Parameter(description = "레이아웃 (FOREGROUND, BACKGROUND, FIGURE)") @RequestParam(required = false) layout: String?,
    ): Map<String, List<ItemResponse>> {
        val items =
            when {
                categoryId != null -> itemRepository.findAllByIsActiveTrueAndCategoryId(categoryId)
                rarity != null -> itemRepository.findAllByIsActiveTrueAndRarity(Rarity.valueOf(rarity))
                layout != null -> itemRepository.findAllByIsActiveTrueAndLayout(ItemLayout.valueOf(layout))
                else -> itemRepository.findAllByIsActiveTrue()
            }
        return mapOf("items" to items.map { it.toResponse() })
    }

    @Operation(summary = "아이템 상세 조회")
    @GetMapping("/{itemId}")
    fun getItem(
        @PathVariable itemId: Long,
    ): ItemResponse {
        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow {
                    com.terraworld.common.exception
                        .BusinessException(com.terraworld.common.exception.ErrorCode.ITEM_NOT_FOUND)
                }
        return item.toResponse()
    }

    private fun Item.toResponse() =
        ItemResponse(
            id = id,
            slug = slug,
            name = name,
            description = description,
            categoryId = category?.id,
            categoryName = category?.name,
            priceType = priceType.name,
            priceAmount = priceAmount,
            tokenPrice = tokenPrice,
            rarity = rarity.name,
            assetUrl = assetUrl,
            layout = layout.name,
            isAnimated = isAnimated,
        )
}
