package com.terraworld.api.terrarium

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfig
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.EvolutionStage
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull
import io.terraworld.api.model.EvolutionStage as ApiEvolutionStage

/**
 * TerrariumService 의 핵심 분기 커버 (Spring context 없이 in-memory fake + mock).
 *
 * - heartClick: 해피 (basicCoin += 1, reward/updatedBasicCoins 정확 일치) / USER_NOT_FOUND
 * - getTerrarium: 해피 (evolutionStage 매핑 = toApiEvolutionStageOrNull) / TERRARIUM_NOT_FOUND
 * - computeWiltingState: 임계치 경계(3/7/14일) 직전·직후 모두 잠금 — stage 0/1/2/3
 *   (TerrariumService.WILT_STAGE_1/2/3_DAYS = 3/7/14, days>=14->3 / >=7->2 / >=3->1 / else 0)
 */
class TerrariumServiceTest {
    private lateinit var terrariumRepo: FakeTerrariumRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var levelConfigRepo: FakeLevelConfigRepository
    private lateinit var recordRepo: FakeRecordRepository
    private lateinit var service: TerrariumService

    private lateinit var user: User
    private lateinit var background: TerrariumBackground

    @BeforeEach
    fun setup() {
        terrariumRepo = FakeTerrariumRepository()
        userRepo = FakeUserRepository()
        levelConfigRepo = FakeLevelConfigRepository()
        recordRepo = FakeRecordRepository()

        // getTerrarium 경로에서만 쓰이지만 데이터 제어가 필요 없는 의존성은 mock.
        val terrariumPlacementRepository = org.mockito.Mockito.mock(TerrariumPlacementRepository::class.java)
        val userItemRepository = org.mockito.Mockito.mock(UserItemRepository::class.java)
        val itemRepository = org.mockito.Mockito.mock(ItemRepository::class.java)
        val placementHistoryRepository = org.mockito.Mockito.mock(TerrariumPlacementHistoryRepository::class.java)
        val entitlementService = org.mockito.Mockito.mock(EntitlementService::class.java)

        service =
            TerrariumService(
                terrariumRepo,
                terrariumPlacementRepository,
                userRepo,
                userItemRepository,
                itemRepository,
                levelConfigRepo,
                recordRepo,
                placementHistoryRepository,
                entitlementService,
            )

        // PALUDARIUM(unlockLevel=5) 까지 해금되도록 level 5 사용자.
        user = User(id = "user-1", nickname = "테스터", level = 5, basicCoin = 100)
        userRepo.save(user)

        background = TerrariumBackground(id = 1L, name = "기본 배경", assetUrl = "https://cdn/bg.png")
        levelConfigRepo.save(LevelConfig(level = 5, requiredExp = 1000, maxItems = 8))
    }

    private fun saveTerrarium(stage: EvolutionStage = EvolutionStage.PALUDARIUM): Terrarium {
        val terrarium =
            Terrarium(
                id = 10L,
                user = user,
                background = background,
                evolutionStage = stage,
            )
        terrariumRepo.save(terrarium)
        return terrarium
    }

    // ─── heartClick ────────────────────────────────────────────

    @Test
    fun `heartClick 해피 패스 — basicCoin += 1 + reward 와 updatedBasicCoins 가 DB 와 정확히 일치`() {
        val response = service.heartClick("user-1")

        assertEquals(1.0, response.reward)
        assertEquals(101.0, response.updatedBasicCoins)

        val u = userRepo.findById("user-1").get()
        assertEquals(101L, u.basicCoin)
    }

    @Test
    fun `heartClick — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.heartClick("ghost")
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    // ─── getTerrarium ──────────────────────────────────────────

    @Test
    fun `getTerrarium 해피 패스 — evolutionStage 매핑 + 기록 없으면 wilting stage 0`() {
        saveTerrarium(EvolutionStage.PALUDARIUM)

        val response = service.getTerrarium("user-1")

        assertEquals(10L, response.terrariumId)
        assertEquals(1L, response.background.id)
        assertEquals("기본 배경", response.background.name)
        // toApiEvolutionStageOrNull: domain PALUDARIUM → generated PALUDARIUM
        assertEquals(ApiEvolutionStage.PALUDARIUM, response.evolutionStage)
        // level 5 → POT(1)/BOTTLE(2)/PALUDARIUM(5) 해금, WORLD(8)/CUSTOM(10) 미해금
        assertEquals(
            listOf(ApiEvolutionStage.POT, ApiEvolutionStage.BOTTLE, ApiEvolutionStage.PALUDARIUM),
            response.unlockedStages,
        )
        // maxSlots = minOf(5, maxItems=8) = 5
        assertEquals(5, response.maxSlots)
        // 기록 없음 → stage 0, daysSinceRecord null
        assertEquals(0, response.wilting?.stage)
        assertNull(response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium — 존재하지 않는 테라리움은 TERRARIUM_NOT_FOUND`() {
        // user 는 있으나 terrarium 미생성
        val ex =
            assertThrows<BusinessException> {
                service.getTerrarium("user-1")
            }
        assertEquals(ErrorCode.TERRARIUM_NOT_FOUND, ex.errorCode)
    }

    // computeWiltingState: 임계치 3/7/14일(WILT_STAGE_1/2/3_DAYS)을 경계 양쪽에서 잠근다.
    // 중간 대역(5/10일) 값은 임계치가 3->5 / 7->10 으로 회귀해도 통과해버리므로
    // "직전 일수 → 낮은 stage" + "임계 당일 → 높은 stage" 를 한 쌍으로 검증.
    // 모든 seeding 은 KstTime.today().minusDays(N) 상대 기준 (절대 날짜 hardcode 금지).

    @Test
    fun `getTerrarium wilting — 오늘 기록이면 stage 0`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(0)

        val response = service.getTerrarium("user-1")

        assertEquals(0, response.wilting?.stage)
        assertEquals(0, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 2일 전(임계 3 직전)이면 stage 0`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(2)

        val response = service.getTerrarium("user-1")

        assertEquals(0, response.wilting?.stage)
        assertEquals(2, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 3일 전(임계 3 당일)이면 stage 1`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(3)

        val response = service.getTerrarium("user-1")

        assertEquals(1, response.wilting?.stage)
        assertEquals(3, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 6일 전(임계 7 직전)이면 stage 1`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(6)

        val response = service.getTerrarium("user-1")

        assertEquals(1, response.wilting?.stage)
        assertEquals(6, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 7일 전(임계 7 당일)이면 stage 2`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(7)

        val response = service.getTerrarium("user-1")

        assertEquals(2, response.wilting?.stage)
        assertEquals(7, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 13일 전(임계 14 직전)이면 stage 2`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(13)

        val response = service.getTerrarium("user-1")

        assertEquals(2, response.wilting?.stage)
        assertEquals(13, response.wilting?.daysSinceRecord)
    }

    @Test
    fun `getTerrarium wilting — 14일 전(임계 14 당일)이면 stage 3`() {
        saveTerrarium()
        recordRepo.maxRecordedDate = KstTime.today().minusDays(14)

        val response = service.getTerrarium("user-1")

        assertEquals(3, response.wilting?.stage)
        assertEquals(14, response.wilting?.daysSinceRecord)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeTerrariumRepository :
        FakeJpaRepository<Terrarium, Long>(),
        TerrariumRepository {
        override fun extractId(entity: Terrarium): Long = entity.id

        override fun findByUserId(userId: String): Optional<Terrarium> = Optional.ofNullable(store.values.firstOrNull { it.user.id == userId })
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeLevelConfigRepository :
        FakeJpaRepository<LevelConfig, Int>(),
        LevelConfigRepository {
        override fun extractId(entity: LevelConfig): Int = entity.level

        override fun findByLevel(level: Int): Optional<LevelConfig> = Optional.ofNullable(store[level])
    }

    private class FakeRecordRepository :
        FakeJpaRepository<ActivityRecord, Long>(),
        RecordRepository {
        var maxRecordedDate: LocalDate? = null

        override fun extractId(entity: ActivityRecord): Long = error("not needed in this test")

        override fun findMaxRecordedDate(userId: String): LocalDate? = maxRecordedDate

        override fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            pageable: org.springframework.data.domain.Pageable,
        ): org.springframework.data.domain.Page<ActivityRecord> =
            org.springframework.data.domain.Page
                .empty()

        override fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
            userId: String,
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityRecord> = emptyList()

        override fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
            userId: String,
            recordedDate: LocalDate,
            categoryId: Long,
        ): Long = 0

        override fun countByUserIdAndIsDeletedFalse(userId: String): Long = 0

        override fun countByUserIdAndRecordedDateAndIsDeletedFalse(
            userId: String,
            date: LocalDate,
        ): Long = 0

        override fun countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(
            userId: String,
            date: LocalDate,
        ): Long = 0

        override fun countByCategoryGrouped(userId: String): List<Array<Any>> = emptyList()

        override fun findEngagementRanking(
            start: LocalDate,
            end: LocalDate,
            pageable: org.springframework.data.domain.Pageable,
        ): List<Array<Any>> = emptyList()

        override fun countByUserAndPeriod(
            userId: String,
            start: LocalDate,
            end: LocalDate,
        ): Long = 0
    }
}
