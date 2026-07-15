package com.terraworld.api.item

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.ItemRepository
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
    // BE-08: 카탈로그 목록은 캐시 컴포넌트 경유 (필터 분기/매핑 로직 이동, semantics 동일)
    private val itemCatalogService: ItemCatalogService,
) : ShopApi {
    @Operation(summary = "아이템 목록 조회")
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun listItems(
        categoryId: Long?,
        rarity: String?,
        layout: String?,
    ): ResponseEntity<ItemListResponse> = ResponseEntity.ok(ItemListResponse(items = itemCatalogService.listActiveItems(categoryId, rarity, layout)))

    @Operation(summary = "아이템 상세 조회")
    // BE-11: listItems 와 동일한 readOnly tx — ItemMapper 가 lazy category 를 읽으므로
    // tx 부재 시 LazyInitializationException 500 (listItems 만 고친 반쪽 fix 보완).
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    override fun getItem(itemId: Long): ResponseEntity<ItemResponse> {
        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }
        return ResponseEntity.ok(ItemMapper.toApi(item))
    }
}
