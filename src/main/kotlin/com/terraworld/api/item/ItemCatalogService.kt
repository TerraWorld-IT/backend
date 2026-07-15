package com.terraworld.api.item

import com.terraworld.common.cache.CacheNames
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.Rarity
import io.terraworld.api.model.ItemResponse
import org.springframework.cache.annotation.Cacheable
import org.springframework.stereotype.Service

/**
 * BE-08 (2026-07-15 성능 감사): 활성 아이템 카탈로그 목록 캐시 (Caffeine TTL 10분).
 *
 * Item 엔티티는 category 가 LAZY 라 엔티티 자체를 캐시하면 hit 시 detached 상태에서
 * LazyInitializationException — 반드시 generated DTO([ItemResponse], 불변)로 매핑한 뒤 캐시한다.
 * 캐시 key 는 (categoryId, rarity, layout) SimpleKey 조합 (null 포함 안전).
 *
 * admin 이 isActive 토글/아이템 추가 시 최대 10분 stale 수용 (성능 감사 결정, 무효화 배선 없음).
 * 필터 분기/valueOf fail-fast semantics 는 기존 ItemController.listItems 그대로 이동.
 * 트랜잭션은 호출부(ItemController.listItems @Transactional(readOnly = true))가 소유 —
 * cache miss 시 lazy category 매핑이 그 tx 안에서 수행된다.
 */
@Service
class ItemCatalogService(
    private val itemRepository: ItemRepository,
) {
    @Cacheable(CacheNames.ITEM_CATALOG)
    fun listActiveItems(
        categoryId: Long?,
        rarity: String?,
        layout: String?,
    ): List<ItemResponse> {
        val items =
            when {
                categoryId != null -> itemRepository.findAllByIsActiveTrueAndCategoryId(categoryId)
                rarity != null -> itemRepository.findAllByIsActiveTrueAndRarity(Rarity.valueOf(rarity))
                layout != null -> itemRepository.findAllByIsActiveTrueAndLayout(ItemLayout.valueOf(layout))
                else -> itemRepository.findAllByIsActiveTrue()
            }
        return items.map(ItemMapper::toApi)
    }
}
