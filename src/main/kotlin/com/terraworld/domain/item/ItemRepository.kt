package com.terraworld.domain.item

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface ItemRepository : JpaRepository<Item, Long> {
    fun findAllByIsActiveTrue(): List<Item>

    fun findAllByIsActiveTrueAndCategoryId(categoryId: Long): List<Item>

    fun findAllByIsActiveTrueAndRarity(rarity: Rarity): List<Item>

    fun findAllByIsActiveTrueAndLayout(layout: ItemLayout): List<Item>

    fun findBySlug(slug: String): Optional<Item>
}

interface UserItemRepository : JpaRepository<UserItem, Long> {
    // BE-02 (2026-07-15 성능 감사): 호출부(UserService.getMe / PurchaseService.purchase)가
    // 전 row 의 item.slug 를 읽으므로 @EntityGraph 로 item 을 fetch join — per-row lazy 로드 N+1 제거.
    @EntityGraph(attributePaths = ["item"])
    fun findAllByUserId(userId: String): List<UserItem>

    fun existsByUserIdAndItemId(
        userId: String,
        itemId: Long,
    ): Boolean

    /**
     * BE-03 (2026-07-15 성능 감사): TerrariumService.updatePlacements 의 배치당
     * existsByUserIdAndItemId 루프(N쿼리) 대체 — 요청 아이템 중 소유분 id 집합을 1쿼리로.
     * 호출 전 itemIds 비어있지 않음을 보장할 것 (빈 IN 절 방지).
     */
    @Query("SELECT ui.item.id FROM UserItem ui WHERE ui.user.id = :userId AND ui.item.id IN :itemIds")
    fun findOwnedItemIds(
        @Param("userId") userId: String,
        @Param("itemIds") itemIds: Collection<Long>,
    ): Set<Long>
}
