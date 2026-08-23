package com.terraworld.api.terrarium

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumPlacement
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.TierConfig
import com.terraworld.domain.terrarium.TierConfigRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.PlacementItem
import io.terraworld.api.model.PlacementRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 아프젝 v2 §R1 — 표시 중 병(activeTier) 전환 + 티어별 배치 스코프 (in-memory fake 배치 저장소).
 * - setActiveTier: 해금 범위 내 전환 OK / 미해금 409 TIER_LOCKED / 미존재·비활성 400 / 멱등
 * - 응답: tier = activeTier, highestUnlockedTier, maxSlots = active 티어 슬롯, placedItems 는 active 티어 분만
 * - updatePlacements: active 티어 배치만 교체, 다른 병의 배치는 보존
 * - PlacementService.listForUser / updateFreePosition 도 active 티어 스코프
 */
class TerrariumActiveTierServiceTest {
    private lateinit var terrariumRepo: FakeTerrariumRepository
    private lateinit var placementRepo: FakeTerrariumPlacementRepository
    private lateinit var tierConfigRepo: FakeTierConfigRepository
    private lateinit var userItemRepository: UserItemRepository
    private lateinit var itemRepository: ItemRepository
    private lateinit var service: TerrariumService
    private lateinit var placementService: PlacementService

    private val user = User(id = "user-1", nickname = "테스터")
    private val background = TerrariumBackground(id = 1L, name = "기본 배경", assetUrl = "https://cdn/bg.png")
    private val itemA = Item(id = 10L, slug = "a", name = "A", priceType = PriceType.BASIC, priceAmount = 1, assetUrl = "a.png")
    private val itemB = Item(id = 11L, slug = "b", name = "B", priceType = PriceType.BASIC, priceAmount = 1, assetUrl = "b.png")
    private lateinit var terrarium: Terrarium

    @BeforeEach
    fun setup() {
        terrariumRepo = FakeTerrariumRepository()
        placementRepo = FakeTerrariumPlacementRepository()
        tierConfigRepo = FakeTierConfigRepository()
        userItemRepository = mock(UserItemRepository::class.java)
        itemRepository = mock(ItemRepository::class.java)
        val recordRepository = mock(RecordRepository::class.java)
        val entitlementService = mock(EntitlementService::class.java)
        whenever(entitlementService.hasEntitlement(any(), any())).thenReturn(true)
        whenever(itemRepository.findAllById(any<Iterable<Long>>())).thenReturn(listOf(itemA, itemB))
        whenever(userItemRepository.findOwnedItemIds(any(), any())).thenReturn(setOf(10L, 11L))

        service =
            TerrariumService(
                terrariumRepo,
                mock(com.terraworld.api.currency.CurrencyService::class.java),
                placementRepo,
                mock(UserRepository::class.java),
                userItemRepository,
                itemRepository,
                mock(TerrariumBackgroundRepository::class.java),
                tierConfigRepo,
                recordRepository,
                mock(TerrariumPlacementHistoryRepository::class.java),
                entitlementService,
            )
        placementService = PlacementService(placementRepo, entitlementService)

        tierConfigRepo.save(TierConfig("GLASS_JAR", 1, "유리병", 0, 0, 10, null, level = 1))
        tierConfigRepo.save(TierConfig("LARGE_JAR", 2, "큰유리병", 0, 30, 20, null, level = 2))
        tierConfigRepo.save(TierConfig("GRAND_TANK", 3, "대형", 0, 50, 40, "pigeon", level = 3))
        tierConfigRepo.save(TierConfig("HOUSE_TANK", 4, "하우스형", 0, 0, 24, null, level = 4, isActive = false))

        // 해금 최고 LARGE_JAR, 표시 중 GLASS_JAR
        terrarium = Terrarium(id = 1L, user = user, background = background, tier = "LARGE_JAR", activeTier = "GLASS_JAR")
        terrariumRepo.save(terrarium)
        // GLASS_JAR 병에 A(slot 0), LARGE_JAR 병에 B(slot 0)
        placementRepo.save(TerrariumPlacement(id = 100L, terrarium = terrarium, item = itemA, slotId = 0, tier = "GLASS_JAR"))
        placementRepo.save(TerrariumPlacement(id = 101L, terrarium = terrarium, item = itemB, slotId = 0, tier = "LARGE_JAR", isFreePlacement = true, freeXPixel = 5000, freeYPixel = 5000))
    }

    @Test
    fun `getTerrarium — active 병(GLASS_JAR) 스코프 — tier 는 activeTier, highest 는 LARGE_JAR, maxSlots 10, A 만`() {
        val r = service.getTerrarium("user-1")
        assertEquals("GLASS_JAR", r.activeTier)
        assertEquals("GLASS_JAR", r.tier)
        assertEquals("LARGE_JAR", r.highestUnlockedTier)
        assertEquals(10, r.maxSlots)
        assertEquals(listOf(10L), r.placedItems.map { it.itemId })
    }

    @Test
    fun `setActiveTier — 해금된 LARGE_JAR 로 전환하면 그 병의 배치(B)·슬롯 20·자유배치 좌표가 내려온다`() {
        val r = service.setActiveTier("user-1", "LARGE_JAR")
        assertEquals("LARGE_JAR", r.activeTier)
        assertEquals("LARGE_JAR", r.tier)
        assertEquals(20, r.maxSlots)
        assertEquals(listOf(11L), r.placedItems.map { it.itemId })
        assertEquals(1, r.freePlacements?.size)
        assertEquals("LARGE_JAR", terrariumRepo.findById(1L).get().activeTier)
        // 해금 최고 티어는 그대로
        assertEquals("LARGE_JAR", terrariumRepo.findById(1L).get().tier)
    }

    @Test
    fun `setActiveTier — 미해금 GRAND_TANK 는 TIER_LOCKED, 미존재·비활성은 INVALID_INPUT`() {
        assertEquals(ErrorCode.TIER_LOCKED, assertThrows<BusinessException> { service.setActiveTier("user-1", "GRAND_TANK") }.errorCode)
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows<BusinessException> { service.setActiveTier("user-1", "NOPE") }.errorCode)
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows<BusinessException> { service.setActiveTier("user-1", "HOUSE_TANK") }.errorCode)
        assertEquals("GLASS_JAR", terrariumRepo.findById(1L).get().activeTier)
    }

    @Test
    fun `setActiveTier — 이미 표시 중인 티어는 변경 없이 200 (멱등)`() {
        val r = service.setActiveTier("user-1", "GLASS_JAR")
        assertEquals("GLASS_JAR", r.activeTier)
    }

    @Test
    fun `updatePlacements — active 병(GLASS_JAR) 배치만 교체, LARGE_JAR 병의 B 는 보존`() {
        val r =
            service.updatePlacements(
                "user-1",
                PlacementRequest(placedItems = listOf(PlacementItem(itemId = 11L, slotId = 3))),
            )
        assertEquals(listOf(11L), r.placedItems.map { it.itemId })
        assertEquals(3, r.placedItems[0].slotId)
        val all = placementRepo.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.tier == "LARGE_JAR" && it.item.id == 11L && it.slotId == 0 && it.isFreePlacement })
        assertTrue(all.any { it.tier == "GLASS_JAR" && it.item.id == 11L && it.slotId == 3 })
        assertTrue(all.none { it.tier == "GLASS_JAR" && it.item.id == 10L })
    }

    @Test
    fun `updatePlacements — 슬롯 범위는 active 병의 슬롯 수 (GLASS_JAR 10 → slot 10 은 INVALID_SLOT)`() {
        val ex =
            assertThrows<BusinessException> {
                service.updatePlacements("user-1", PlacementRequest(placedItems = listOf(PlacementItem(itemId = 10L, slotId = 10))))
            }
        assertEquals(ErrorCode.INVALID_SLOT, ex.errorCode)
    }

    @Test
    fun `PlacementService listForUser — active 병의 배치만, 다른 병 배치 편집은 PLACEMENT_NOT_FOUND`() {
        assertEquals(listOf(100L), placementService.listForUser("user-1").map { it.placementId })
        val ex = assertThrows<BusinessException> { placementService.updateFreePosition("user-1", 101L, 0.1, 0.2) }
        assertEquals(ErrorCode.PLACEMENT_NOT_FOUND, ex.errorCode)
        // 전환 후에는 B 편집 가능
        service.setActiveTier("user-1", "LARGE_JAR")
        assertEquals(listOf(101L), placementService.listForUser("user-1").map { it.placementId })
        assertEquals(0.1, placementService.updateFreePosition("user-1", 101L, 0.1, 0.2).posX)
    }

    // ─── 페이크 ─────────────────────────────────────────────────

    private class FakeTerrariumRepository :
        FakeJpaRepository<Terrarium, Long>(),
        TerrariumRepository {
        override fun extractId(entity: Terrarium): Long = entity.id

        override fun findByUserId(userId: String): Optional<Terrarium> = Optional.ofNullable(store.values.firstOrNull { it.user.id == userId })

        override fun findAllWiltScanProjections() = emptyList<com.terraworld.domain.terrarium.WiltScanProjection>()

        override fun casTier(
            id: Long,
            current: String,
            target: String,
            updatedAt: java.time.LocalDateTime,
        ): Int {
            val t = store[id] ?: return 0
            if (t.tier != current) return 0
            t.tier = target
            return 1
        }
    }

    private class FakeTerrariumPlacementRepository :
        FakeJpaRepository<TerrariumPlacement, Long>(),
        TerrariumPlacementRepository {
        private var seq = 500L

        override fun extractId(entity: TerrariumPlacement): Long = entity.id

        override fun assignId(entity: TerrariumPlacement): TerrariumPlacement {
            if (entity.id != 0L) return entity
            val idField = TerrariumPlacement::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, seq++)
            return entity
        }

        override fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement> = store.values.filter { it.terrarium.id == terrariumId }

        override fun findActiveTierPlacementsByUserId(userId: String): List<TerrariumPlacement> = store.values.filter { it.terrarium.user.id == userId && it.tier == it.terrarium.activeTier }.sortedBy { it.id }

        override fun findAllByTerrariumIdAndTierOrderById(
            terrariumId: Long,
            tier: String,
        ): List<TerrariumPlacement> = store.values.filter { it.terrarium.id == terrariumId && it.tier == tier }.sortedBy { it.id }

        override fun deleteAllByTerrariumIdAndTier(
            terrariumId: Long,
            tier: String,
        ) {
            store.values.filter { it.terrarium.id == terrariumId && it.tier == tier }.forEach { store.remove(it.id) }
        }
    }

    private class FakeTierConfigRepository :
        FakeJpaRepository<TierConfig, String>(),
        TierConfigRepository {
        override fun extractId(entity: TierConfig): String = entity.tier

        override fun findAllByOrderByTierOrderAsc(): List<TierConfig> = store.values.sortedBy { it.tierOrder }

        override fun findAllByIsActiveTrueOrderByTierOrderAsc(): List<TierConfig> = store.values.filter { it.isActive }.sortedBy { it.tierOrder }

        override fun findSlotsByTier(tier: String): Int? = store[tier]?.slots
    }
}
