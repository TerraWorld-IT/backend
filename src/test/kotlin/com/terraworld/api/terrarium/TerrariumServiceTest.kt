package com.terraworld.api.terrarium

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.HeartRewardRepository
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.TierConfig
import com.terraworld.domain.terrarium.TierConfigRepository
import com.terraworld.domain.terrarium.WiltScanProjection
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.wallet.UserCurrencyBalance
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    private lateinit var tierConfigRepo: FakeTierConfigRepository
    private lateinit var recordRepo: FakeRecordRepository
    private lateinit var heartRewardRepo: FakeHeartRewardRepository
    private lateinit var currencyService: com.terraworld.api.currency.CurrencyService
    private lateinit var service: TerrariumService

    private lateinit var user: User
    private lateinit var background: TerrariumBackground

    @BeforeEach
    fun setup() {
        terrariumRepo = FakeTerrariumRepository()
        userRepo = FakeUserRepository()
        tierConfigRepo = FakeTierConfigRepository()
        recordRepo = FakeRecordRepository()
        heartRewardRepo = FakeHeartRewardRepository()

        // getTerrarium 경로에서만 쓰이지만 데이터 제어가 필요 없는 의존성은 mock.
        val terrariumPlacementRepository = org.mockito.Mockito.mock(TerrariumPlacementRepository::class.java)
        val userItemRepository = org.mockito.Mockito.mock(UserItemRepository::class.java)
        val itemRepository = org.mockito.Mockito.mock(ItemRepository::class.java)
        val terrariumBackgroundRepository = org.mockito.Mockito.mock(com.terraworld.domain.terrarium.TerrariumBackgroundRepository::class.java)
        val placementHistoryRepository = org.mockito.Mockito.mock(TerrariumPlacementHistoryRepository::class.java)
        val entitlementService = org.mockito.Mockito.mock(EntitlementService::class.java)
        currencyService = org.mockito.Mockito.mock(com.terraworld.api.currency.CurrencyService::class.java)
        // 낙서장 P1: 하트 보상은 CurrencyService.credit(신 substrate). credit 반환값(신 COIN 잔액)을 응답에 사용.
        org.mockito.kotlin
            .whenever(currencyService.credit(org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.any(), org.mockito.kotlin.anyOrNull(), org.mockito.kotlin.anyOrNull()))
            .thenReturn(101L)

        service =
            TerrariumService(
                terrariumRepo,
                currencyService,
                terrariumPlacementRepository,
                userRepo,
                userItemRepository,
                itemRepository,
                terrariumBackgroundRepository,
                tierConfigRepo,
                recordRepo,
                placementHistoryRepository,
                entitlementService,
                // 티어별 배경(V40): mock → 행 없음 → terrariums.background 폴백 경로
                org.mockito.Mockito.mock(com.terraworld.domain.terrarium.TerrariumTierBackgroundRepository::class.java),
                heartRewardRepo,
                TerrariumProperties(),
            )

        user = User(id = "user-1", nickname = "테스터")
        userRepo.save(user)

        background = TerrariumBackground(id = 1L, name = "기본 배경", assetUrl = "https://cdn/bg.png")
        // 낙서장 P2: maxSlots = 티어 슬롯 (GLASS_JAR = 6)
        tierConfigRepo.save(TierConfig("GLASS_JAR", 1, "유리병", 0, 0, 6, null))
    }

    private fun saveTerrarium(tier: String = "GLASS_JAR"): Terrarium {
        val terrarium =
            Terrarium(
                id = 10L,
                user = user,
                background = background,
                tier = tier,
            )
        terrariumRepo.save(terrarium)
        return terrarium
    }

    // ─── heartClick ────────────────────────────────────────────

    @Test
    fun `heartClick 해피 패스 — reward 1 + updatedBasicCoins 는 credit 반환 신 잔액`() {
        val response = service.heartClick("user-1")

        assertEquals(1.0, response.reward)
        // updatedBasicCoins = CurrencyService.credit 반환값(신 COIN 잔액, stub 101)
        assertEquals(101.0, response.updatedBasicCoins)
    }

    @Test
    fun `heartClick — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.heartClick("ghost")
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `heartClick 같은 KST 날짜의 11번째 하트 — reward 0이고 코인을 지급하지 않는다`() {
        val firstHalfHourKst =
            KstTime
                .today()
                .atTime(0, 30)
                .atZone(KstTime.ZONE)
                .withZoneSameInstant(ZoneOffset.UTC)
                .toLocalDateTime()
        repeat(10) { heartRewardRepo.grantedAt += firstHalfHourKst }
        whenever(currencyService.balances("user-1"))
            .thenReturn(listOf(UserCurrencyBalance("user-1", "COIN", amount = 110L)))

        val response = service.heartClick("user-1")

        assertEquals(0.0, response.reward)
        assertEquals(110.0, response.updatedBasicCoins)
        verify(currencyService, never())
            .credit(
                any(),
                any(),
                any(),
                any(),
                anyOrNull(),
                anyOrNull(),
            )
    }

    // ─── getTerrarium ──────────────────────────────────────────

    @Test
    fun `getTerrarium 해피 패스 — tier 매핑 + maxSlots + 기록 없으면 wilting stage 0`() {
        saveTerrarium("GLASS_JAR")

        val response = service.getTerrarium("user-1")

        assertEquals(10L, response.terrariumId)
        assertEquals(1L, response.background.id)
        assertEquals("기본 배경", response.background.name)
        // 낙서장 P2: tier + maxSlots = tier_configs.slots (GLASS_JAR = 6)
        assertEquals("GLASS_JAR", response.tier)
        assertEquals(6, response.maxSlots)
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

    private class FakeHeartRewardRepository : HeartRewardRepository {
        val grantedAt = mutableListOf<LocalDateTime>()

        override fun countGrantedRewards(
            userId: String,
            dayStartUtc: LocalDateTime,
            nextDayStartUtc: LocalDateTime,
        ): Long = grantedAt.count { !it.isBefore(dayStartUtc) && it.isBefore(nextDayStartUtc) }.toLong()

        override fun acquireDailyLock(key: String): Int = 1
    }

    private class FakeTerrariumRepository :
        FakeJpaRepository<Terrarium, Long>(),
        TerrariumRepository {
        override fun extractId(entity: Terrarium): Long = entity.id

        override fun findByUserId(userId: String): Optional<Terrarium> = Optional.ofNullable(store.values.firstOrNull { it.user.id == userId })

        override fun findAllWiltScanProjections(): List<WiltScanProjection> =
            store.values.map {
                object : WiltScanProjection {
                    override val userId = it.user.id
                    override val wiltRecoveredAt = it.wiltRecoveredAt
                }
            }

        override fun casTier(
            id: Long,
            current: String,
            target: String,
            updatedAt: LocalDateTime,
        ): Int {
            val t = store[id] ?: return 0
            if (t.tier != current) return 0
            t.tier = target
            t.updatedAt = updatedAt
            return 1
        }
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id

        override fun acquireBootstrapLock(key: String): Int = 1
    }

    private class FakeTierConfigRepository :
        FakeJpaRepository<TierConfig, String>(),
        TierConfigRepository {
        override fun extractId(entity: TierConfig): String = entity.tier

        override fun findAllByOrderByTierOrderAsc(): List<TierConfig> = store.values.sortedBy { it.tierOrder }

        override fun findAllByIsActiveTrueOrderByTierOrderAsc(): List<TierConfig> = store.values.filter { it.isActive }.sortedBy { it.tierOrder }

        // BE-08: TerrariumService 가 findById 대신 캐시 대상 scalar 조회 사용
        override fun findSlotsByTier(tier: String): Int? = store[tier]?.slots
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

        // BE-16: 월 조회 pageable 계약 정합 — Page 반환
        override fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            from: LocalDate,
            to: LocalDate,
            pageable: org.springframework.data.domain.Pageable,
        ): org.springframework.data.domain.Page<ActivityRecord> =
            org.springframework.data.domain.Page
                .empty()

        override fun findAllByUserIdAndCategoryIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            categoryId: Long,
            pageable: org.springframework.data.domain.Pageable,
        ): org.springframework.data.domain.Page<ActivityRecord> =
            org.springframework.data.domain.Page
                .empty()

        override fun findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            categoryId: Long,
            from: LocalDate,
            to: LocalDate,
            pageable: org.springframework.data.domain.Pageable,
        ): org.springframework.data.domain.Page<ActivityRecord> =
            org.springframework.data.domain.Page
                .empty()

        override fun acquireRecordDailyLock(key: String): Int = 1

        // 아프젝 v2: 친구 스코프 engagement 랭킹 — 본 테스트 비대상
        override fun findEngagementRankingAmong(
            userIds: Collection<String>,
            start: LocalDate,
            end: LocalDate,
            pageable: org.springframework.data.domain.Pageable,
        ): List<Array<Any>> = emptyList()

        override fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
            userId: String,
            recordedDate: LocalDate,
            categoryId: Long,
        ): Long = 0

        override fun countByUserIdAndRecordedDateAndCategoryId(
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

        // BE-05 / ADD-BE-02: 신규 배치·집계 메서드 — 본 테스트 경로 미사용 (zero stub)
        override fun findMaxRecordedDatePerUser(): List<com.terraworld.domain.record.UserMaxRecordedDateProjection> = emptyList()

        override fun countStatisticsSummary(
            userId: String,
            today: LocalDate,
            weekAgo: LocalDate,
        ): com.terraworld.domain.record.RecordStatisticsSummary =
            object : com.terraworld.domain.record.RecordStatisticsSummary {
                override val todayCount: Long? = null
                override val weekCount: Long? = null
                override val totalCount: Long = 0
            }

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
