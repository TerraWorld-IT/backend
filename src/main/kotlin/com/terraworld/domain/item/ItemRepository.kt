package com.terraworld.domain.item

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    /**
     * 아프젝 v2: 원자적 멱등 소유 부여 (ON CONFLICT DO NOTHING) — GrantService ITEM executor.
     * idx_user_items_unique(user_id, item_id) 충돌 시 0 (이미 보유 → 수량 증가 없음), rollback-only 오염 없음.
     * @return 1 = 신규 소유 / 0 = 이미 보유
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO user_items (user_id, item_id, quantity, acquired_at)
            VALUES (:userId, :itemId, 1, NOW())
            ON CONFLICT (user_id, item_id) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("itemId") itemId: Long,
    ): Int

    /**
     * 아프젝 v2 items 랭킹 — 사용자별 보유 아이템(distinct slug) 수, 월 무관 현재값. 결과: [userId, count] desc.
     * slug 가 NULL 인 아이템은 distinct 집계에서 제외된다 (계약: `user_items` distinct slug 수).
     */
    @Query(
        """
        SELECT ui.user.id, COUNT(DISTINCT ui.item.slug)
        FROM UserItem ui
        WHERE ui.item.slug IS NOT NULL
        GROUP BY ui.user.id
        ORDER BY COUNT(DISTINCT ui.item.slug) DESC, ui.user.id ASC
        """,
    )
    fun findItemsRanking(pageable: Pageable): List<Array<Any>>

    /** 아프젝 v2 items 랭킹 — 친구 스코프(본인 + 수락된 친구 userId 집합) 한정. */
    @Query(
        """
        SELECT ui.user.id, COUNT(DISTINCT ui.item.slug)
        FROM UserItem ui
        WHERE ui.item.slug IS NOT NULL AND ui.user.id IN :userIds
        GROUP BY ui.user.id
        ORDER BY COUNT(DISTINCT ui.item.slug) DESC, ui.user.id ASC
        """,
    )
    fun findItemsRankingAmong(
        @Param("userIds") userIds: Collection<String>,
        pageable: Pageable,
    ): List<Array<Any>>

    /** 아프젝 v2 items 랭킹 — 특정 사용자의 보유 아이템(distinct slug) 수 (자기 순위 계산용, 0 가능). */
    @Query("SELECT COUNT(DISTINCT ui.item.slug) FROM UserItem ui WHERE ui.user.id = :userId AND ui.item.slug IS NOT NULL")
    fun countDistinctSlugsByUserId(
        @Param("userId") userId: String,
    ): Long
}
