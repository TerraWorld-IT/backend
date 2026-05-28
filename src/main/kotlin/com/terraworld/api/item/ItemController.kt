package com.terraworld.api.item

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
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
 * Entity → DTO 매핑은 [ItemMapper] 가 담당 (ARCH-001/002 cleanup).
 * generated enum 변환 시 spec drift 가드 (ADR-019) 적용 — DB 의 enum 값이
 * spec 에 없으면 warn log + fail-fast.
 */
@Tag(name = "Shop", description = "상점 아이템 API (목록 / 상세)")
@RestController
@RequestMapping("/api/v1")
class ItemController(
    private val itemRepository: ItemRepository,
) : ShopApi {
    @Operation(summary = "아이템 목록 조회")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
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
        return ResponseEntity.ok(ItemListResponse(items = items.map(ItemMapper::toApi)))
    }

    @Operation(summary = "아이템 상세 조회")
    override fun getItem(itemId: Long): ResponseEntity<ItemResponse> {
        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }
        return ResponseEntity.ok(ItemMapper.toApi(item))
    }
}
