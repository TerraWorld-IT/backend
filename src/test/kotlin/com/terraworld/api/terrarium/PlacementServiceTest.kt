package com.terraworld.api.terrarium

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumPlacement
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.user.User
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PlacementService 의 분기 커버.
 * - 좌표 변환 (0~1 비율 <-> 0~10000 permille): 저장/조회 round-trip + 클램핑
 * - entitlement 게이트: 보유 시 해피 / 미보유 시 403 FORBIDDEN
 * - 소유권 게이트: 본인 placement 아니면 403 FORBIDDEN
 * - 미존재 placement: PLACEMENT_NOT_FOUND
 *
 * placementRepository 는 데이터 제어가 필요 -> FakeTerrariumPlacementRepository.
 * entitlementService 는 게이트 분기만 제어하면 됨 -> Mockito.mock.
 */
class PlacementServiceTest {
    private lateinit var placementRepo: FakeTerrariumPlacementRepository
    private lateinit var entitlementService: EntitlementService
    private lateinit var service: PlacementService

    private lateinit var owner: User
    private lateinit var terrarium: Terrarium
    private lateinit var item: Item

    @BeforeEach
    fun setup() {
        placementRepo = FakeTerrariumPlacementRepository()
        entitlementService = Mockito.mock(EntitlementService::class.java)
        service = PlacementService(placementRepo, entitlementService)

        owner = User(id = "user-1", nickname = "테스터")
        val background =
            TerrariumBackground(
                id = 1L,
                name = "기본 배경",
                assetUrl = "https://cdn.example/bg.png",
            )
        terrarium = Terrarium(id = 1L, user = owner, background = background)
        item =
            Item(
                id = 10L,
                name = "선인장",
                priceType = PriceType.BASIC,
                priceAmount = 100,
                assetUrl = "https://cdn.example/cactus.png",
                layout = ItemLayout.FOREGROUND,
            )

        // placementId=100, 현재 free 위치는 미설정 (null -> 조회 시 0.0 비율)
        placementRepo.save(
            TerrariumPlacement(id = 100L, terrarium = terrarium, item = item),
        )
    }

    private fun grantFreePlacement() {
        Mockito
            .`when`(entitlementService.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
            .thenReturn(true)
    }

    // ─── 좌표 변환 + 해피 패스 ──────────────────────────────────

    @Test
    fun `updateFreePosition 해피 패스 — 0~1 비율이 permille 로 저장되고 다시 비율로 반환`() {
        grantFreePlacement()

        val result = service.updateFreePosition("user-1", 100L, posX = 0.25, posY = 0.5)

        // 응답은 0~1 비율 그대로 (round-trip 정밀도 0.01%)
        assertEquals(0.25, result.posX)
        assertEquals(0.5, result.posY)
        assertTrue(result.isFreePlacement)
        assertEquals(100L, result.placementId)
        assertEquals(10L, result.itemId)
        assertEquals("선인장", result.itemName)
        assertEquals(ItemLayout.FOREGROUND.name, result.itemLayout)

        // 저장은 permille(0~10000) 컬럼
        val saved = placementRepo.findById(100L).get()
        assertEquals(2500, saved.freeXPixel)
        assertEquals(5000, saved.freeYPixel)
        assertTrue(saved.isFreePlacement)
    }

    @Test
    fun `updateFreePosition — 0~1 범위 밖 좌표는 0~1 로 클램핑되어 저장`() {
        grantFreePlacement()

        val result = service.updateFreePosition("user-1", 100L, posX = 1.5, posY = -0.3)

        // 1.5 -> 1.0 (10000), -0.3 -> 0.0 (0)
        assertEquals(1.0, result.posX)
        assertEquals(0.0, result.posY)

        val saved = placementRepo.findById(100L).get()
        assertEquals(10_000, saved.freeXPixel)
        assertEquals(0, saved.freeYPixel)
    }

    @Test
    fun `listForUser — permille 저장값이 0~1 비율로 변환되어 반환`() {
        // 저장된 permille 값을 직접 세팅
        val placement = placementRepo.findById(100L).get()
        placement.freeXPixel = 7500
        placement.freeYPixel = 1000
        placement.isFreePlacement = true

        val items = service.listForUser("user-1")

        assertEquals(1, items.size)
        assertEquals(0.75, items[0].posX)
        assertEquals(0.1, items[0].posY)
        assertTrue(items[0].isFreePlacement)
    }

    // ─── entitlement / 소유권 게이트 ───────────────────────────

    @Test
    fun `updateFreePosition — free_placement entitlement 없으면 FORBIDDEN`() {
        // hasEntitlement default(mock) = false
        val ex =
            assertThrows<BusinessException> {
                service.updateFreePosition("user-1", 100L, posX = 0.5, posY = 0.5)
            }
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode)

        // 게이트에서 막혔으므로 저장 변경 없음
        val saved = placementRepo.findById(100L).get()
        assertEquals(null, saved.freeXPixel)
        assertEquals(false, saved.isFreePlacement)
    }

    @Test
    fun `updateFreePosition — 존재하지 않는 placement 는 PLACEMENT_NOT_FOUND`() {
        grantFreePlacement()

        val ex =
            assertThrows<BusinessException> {
                service.updateFreePosition("user-1", 999L, posX = 0.5, posY = 0.5)
            }
        assertEquals(ErrorCode.PLACEMENT_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `updateFreePosition — 타인 소유 placement 는 FORBIDDEN`() {
        // user-2 는 entitlement 보유하지만 placement 소유자는 user-1
        Mockito
            .`when`(entitlementService.hasEntitlement("user-2", UserEntitlementId.FREE_PLACEMENT))
            .thenReturn(true)

        val ex =
            assertThrows<BusinessException> {
                service.updateFreePosition("user-2", 100L, posX = 0.5, posY = 0.5)
            }
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode)
    }

    // ─── Fake ──────────────────────────────────────────────────

    private class FakeTerrariumPlacementRepository :
        com.terraworld.test.FakeJpaRepository<TerrariumPlacement, Long>(),
        TerrariumPlacementRepository {
        override fun extractId(entity: TerrariumPlacement): Long = entity.id

        override fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement> = store.values.filter { it.terrarium.id == terrariumId }

        override fun findAllByTerrariumUserIdOrderById(userId: String): List<TerrariumPlacement> = store.values.filter { it.terrarium.user.id == userId }.sortedBy { it.id }

        override fun deleteAllByTerrariumId(terrariumId: Long) {
            store.values.filter { it.terrarium.id == terrariumId }.forEach { store.remove(it.id) }
        }
    }
}
