package com.terraworld.api.growth

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.api.notification.SpiritArrivedEvent
import com.terraworld.api.reward.RewardService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.growth.GrowthCycleState
import com.terraworld.domain.growth.GrowthInstance
import com.terraworld.domain.growth.GrowthInstanceRepository
import com.terraworld.domain.growth.GrowthSpecies
import com.terraworld.domain.growth.GrowthSpeciesRepository
import com.terraworld.domain.growth.GrowthStage
import com.terraworld.domain.growth.GrowthStageId
import com.terraworld.domain.growth.GrowthStageRepository
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.GrowthItem
import io.terraworld.api.model.GrowthReviveRequest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GrowthService 아프젝 v2 사이클 모델 커버 (§R3). 단일 종(cat SPIRIT)으로 id 충돌 회피.
 * - LOST 판정 고정 4건 (D 23:59 기록 / D+1 00:01 조회 / D+1 23:59 기록 / D+2 00:00 조회) + lost-after-days 설정
 * - advanceAllStreaks: 최초(1) / 연속(+1) / 같은날 멱등 / LOST·COMPLETED 는 진행 없음 / 30 도달 → COMPLETED + 정령 아이템 지급
 * - buyBooster: LOST 409 GROWTH_LOST / 부스터로 30 도달 완료
 * - revive: RUBY 10 차감·진행 유지·ACTIVE / AD nonce 소비(보상 없음) / 보류 당일 409 / 비LOST 멱등
 * - revive-dismiss: 다음날 리셋 / 완료 리셋 + notifyNext 알림 1회 + 해제 / 스케줄러 경로 resetDueById
 */
class GrowthServiceTest {
    private lateinit var speciesRepo: FakeSpeciesRepository
    private lateinit var stageRepo: FakeStageRepository
    private lateinit var instanceRepo: FakeInstanceRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var grantService: GrantService
    private lateinit var rewardService: RewardService
    private lateinit var recordRepository: RecordRepository
    private lateinit var itemRepository: ItemRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: GrowthService
    private val today = KstTime.today()

    @BeforeEach
    fun setup() {
        speciesRepo = FakeSpeciesRepository()
        stageRepo = FakeStageRepository()
        instanceRepo = FakeInstanceRepository()
        currencyService = mock(CurrencyService::class.java)
        grantService = mock(GrantService::class.java)
        rewardService = mock(RewardService::class.java)
        recordRepository = mock(RecordRepository::class.java)
        itemRepository = mock(ItemRepository::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        whenever(grantService.grant(any(), any(), any(), any(), any(), any())).thenReturn(true)
        whenever(itemRepository.findBySlug("cat-spirit"))
            .thenReturn(Optional.of(Item(id = 1L, slug = "cat-spirit", name = "고양이 정령", priceType = PriceType.SPECIAL, priceAmount = 0, assetUrl = "x")))
        service =
            GrowthService(
                speciesRepo,
                stageRepo,
                instanceRepo,
                currencyService,
                grantService,
                rewardService,
                recordRepository,
                itemRepository,
                eventPublisher,
                GrowthProperties(lostAfterDays = 2, reviveRubyCost = 10, goal = 30),
            )

        speciesRepo.save(GrowthSpecies(code = "cat", kind = "SPIRIT", nameKo = "고양이 정령", sortOrder = 1))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 1, threshold = 1, labelKo = "수수께끼 정령"))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 2, threshold = 10, labelKo = "고양이 정령 1단계"))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 3, threshold = 20, labelKo = "고양이 정령 2단계"))
    }

    private fun inst(
        streak: Int = 0,
        bought: Int = 0,
        lastProgress: LocalDateTime? = null,
        state: GrowthCycleState = GrowthCycleState.ACTIVE,
    ): GrowthInstance =
        instanceRepo.save(
            GrowthInstance(id = 1L, userId = "u", speciesCode = "cat", naturalStreak = streak, sparkleBoughtCount = bought, lastProgressAt = lastProgress, cycleState = state),
        )

    private fun cat(): GrowthItem = service.getGrowth("u").items.first { it.speciesCode == "cat" }

    // ─── LOST 판정 고정 4건 ───

    @Test
    fun `LOST 판정 — D 23시59분 기록 후 D+1 00시01분 조회는 ACTIVE`() {
        val d = LocalDate.of(2026, 8, 20)
        assertFalse(service.isLost(d.atTime(23, 59), d.plusDays(1)))
    }

    @Test
    fun `LOST 판정 — D+1 23시59분 기록(연속)은 ACTIVE`() {
        val d = LocalDate.of(2026, 8, 20)
        assertFalse(service.isLost(d.plusDays(1).atTime(23, 59), d.plusDays(1)))
        assertFalse(service.isLost(d.plusDays(1).atTime(23, 59), d.plusDays(2)))
    }

    @Test
    fun `LOST 판정 — D 기록 후 D+1 미기록이면 D+2 00시00분 조회부터 LOST`() {
        val d = LocalDate.of(2026, 8, 20)
        assertTrue(service.isLost(d.atTime(23, 59), d.plusDays(2)))
        assertTrue(service.isLost(d.atStartOfDay(), d.plusDays(3)))
    }

    @Test
    fun `LOST 판정 — 기록 없는 신규 개체는 LOST 아님`() {
        assertFalse(service.isLost(null, today))
    }

    @Test
    fun `getGrowth — 기록 끊김이면 LOST 로 영속 전환 + dormant 호환 + lostAt`() {
        inst(streak = 5, lastProgress = today.minusDays(2).atTime(9, 0))
        val item = cat()
        assertEquals(GrowthItem.CycleState.LOST, item.cycleState)
        assertTrue(item.dormant)
        assertTrue(item.lostAt != null)
        assertEquals(5, item.stampCount)
        assertEquals(GrowthCycleState.LOST, instanceRepo.findById(1L).get().cycleState)
    }

    @Test
    fun `getGrowth — 판정 read 이후 커밋된 D+1 23시59분 기록은 LOST CAS 에 덮어써지지 않는다 (stale read → ACTIVE 유지)`() {
        // D 기록 → D+2 00:00 조회 시점에는 LOST 판정이 참이지만, 조회 read 와 UPDATE 사이에 D+1 23:59 기록 tx 가 커밋된다.
        inst(streak = 5, lastProgress = today.minusDays(2).atTime(9, 0))
        instanceRepo.beforeMarkLost = { g ->
            g.naturalStreak = 6
            g.lastProgressAt = today.minusDays(1).atTime(23, 59)
            g.version += 1
        }
        val item = cat()
        assertEquals(GrowthItem.CycleState.ACTIVE, item.cycleState)
        assertFalse(item.dormant)
        assertNull(item.lostAt)
        assertEquals(6, item.stampCount)
        val stored = instanceRepo.findById(1L).get()
        assertEquals(GrowthCycleState.ACTIVE, stored.cycleState)
        assertNull(stored.lostAt)
        assertEquals(today.minusDays(1).atTime(23, 59), stored.lastProgressAt)
        // 훅 제거 후 같은 관측값이면 정상 전환 — CAS 조건이 항상 0 을 돌려주는 고장이 아님을 대조
        instanceRepo.beforeMarkLost = null
        stored.lastProgressAt = today.minusDays(2).atTime(9, 0)
        assertEquals(GrowthItem.CycleState.LOST, cat().cycleState)
    }

    @Test
    fun `getGrowth — 개체 미생성 종은 진행 0 ACTIVE + stages 3단계 + goal 30 + reviveRubyCost 10`() {
        val item = cat()
        assertEquals(0, item.stampCount)
        assertEquals(GrowthItem.CycleState.ACTIVE, item.cycleState)
        assertEquals(3, item.stages.size)
        assertEquals(10, item.stages[1].threshold)
        assertEquals("수수께끼 정령", item.stageLabel)
        assertEquals(30, item.goal)
        assertEquals(10, item.reviveRubyCost)
        assertEquals("cat:0", item.cycleId)
    }

    // ─── 기록 진행(advanceAllStreaks) ───

    @Test
    fun `advanceAllStreaks 최초 — 개체 생성 streak 1`() {
        service.advanceAllStreaks("u")
        val i = instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!
        assertEquals(1, i.naturalStreak)
        assertEquals(1, i.currentStage)
    }

    @Test
    fun `advanceAllStreaks 연속 — 어제 진행이면 streak++ + 단계 재계산`() {
        inst(streak = 9, lastProgress = today.minusDays(1).atStartOfDay())
        service.advanceAllStreaks("u")
        val i = instanceRepo.findById(1L).get()
        assertEquals(10, i.naturalStreak)
        assertEquals(2, i.currentStage)
    }

    @Test
    fun `advanceAllStreaks 같은날 — 멱등 no-op`() {
        inst(streak = 3, lastProgress = today.atStartOfDay())
        service.advanceAllStreaks("u")
        assertEquals(3, instanceRepo.findById(1L).get().naturalStreak)
    }

    @Test
    fun `advanceAllStreaks LOST — 기록해도 진행하지 않는다 (되살리기 필요)`() {
        inst(streak = 10, lastProgress = today.minusDays(3).atStartOfDay())
        service.advanceAllStreaks("u")
        val i = instanceRepo.findById(1L).get()
        assertEquals(GrowthCycleState.LOST, i.cycleState)
        assertEquals(10, i.naturalStreak)
    }

    @Test
    fun `advanceAllStreaks 30 도달 — COMPLETED + 정령 아이템 ITEM grant + completedToday + resetDue 내일`() {
        inst(streak = 29, lastProgress = today.minusDays(1).atStartOfDay())
        service.advanceAllStreaks("u")
        val i = instanceRepo.findById(1L).get()
        assertEquals(GrowthCycleState.COMPLETED, i.cycleState)
        assertEquals(today, i.completedKstDate)
        assertEquals(today.plusDays(1), i.resetDueKstDate)
        verify(grantService).grant(eq("u"), eq(GrantType.ITEM), eq("cat-spirit"), eq(1L), eq("growth:${i.cycleId}:spirit"), any())
        val item = cat()
        assertTrue(item.completedToday)
        assertEquals(GrowthItem.CycleState.COMPLETED, item.cycleState)
        // 완료 당일 추가 기록은 진행 없음
        service.advanceAllStreaks("u")
        assertEquals(30, instanceRepo.findById(1L).get().naturalStreak)
    }

    // ─── 부스터(buyBooster) ───

    @Test
    fun `buyBooster — SPARKLE 100 debit + sparkleBoughtCount +10 + stage 재계산`() {
        inst(streak = 6)
        val item = service.buyBooster("u", "cat")
        verify(currencyService).debit(eq("u"), eq("SPARKLE"), eq(100L), any(), anyOrNull(), anyOrNull())
        assertEquals(16, item.effectiveProgress)
        assertEquals(2, item.currentStage)
    }

    @Test
    fun `buyBooster LOST — GROWTH_LOST 이고 SPARKLE 미차감`() {
        inst(streak = 5, lastProgress = today.minusDays(4).atStartOfDay())
        val ex = assertThrows<BusinessException> { service.buyBooster("u", "cat") }
        assertEquals(ErrorCode.GROWTH_LOST, ex.errorCode)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `buyBooster 로 30 도달 — COMPLETED + 정령 지급`() {
        inst(streak = 22, lastProgress = today.atStartOfDay())
        val item = service.buyBooster("u", "cat")
        assertEquals(GrowthItem.CycleState.COMPLETED, item.cycleState)
        verify(grantService).grant(eq("u"), eq(GrantType.ITEM), eq("cat-spirit"), any(), any(), any())
    }

    @Test
    fun `buyBooster 미존재 종 — INVALID_INPUT`() {
        assertEquals(ErrorCode.INVALID_INPUT, assertThrows<BusinessException> { service.buyBooster("u", "unknown") }.errorCode)
    }

    // ─── 되살리기 / 보류 ───

    @Test
    fun `revive RUBY — 10 차감, 진행 유지, ACTIVE 복귀, 오늘 기록이 오면 연속으로 이어진다`() {
        inst(streak = 12, lastProgress = today.minusDays(2).atStartOfDay())
        val item = service.revive("u", "cat", GrowthReviveRequest.Method.RUBY, null)
        verify(currencyService).debit(eq("u"), eq("RUBY"), eq(10L), any(), anyOrNull(), anyOrNull())
        assertEquals(GrowthItem.CycleState.ACTIVE, item.cycleState)
        assertEquals(12, item.stampCount)
        assertNull(item.lostAt)
        // 오늘 기록 → 13 (기준일이 어제로 맞춰졌으므로 연속)
        service.advanceAllStreaks("u")
        assertEquals(13, instanceRepo.findById(1L).get().naturalStreak)
    }

    @Test
    fun `revive — 오늘 이미 기록이 있으면 되살리며 오늘 스탬프 반영`() {
        inst(streak = 12, lastProgress = today.minusDays(2).atStartOfDay())
        whenever(recordRepository.findMaxRecordedDate("u")).thenReturn(today)
        val item = service.revive("u", "cat", GrowthReviveRequest.Method.RUBY, null)
        assertEquals(13, item.stampCount)
        service.advanceAllStreaks("u") // 같은날 멱등
        assertEquals(13, instanceRepo.findById(1L).get().naturalStreak)
    }

    @Test
    fun `revive AD — nonce 소비 primitive 만 호출(purpose GROWTH_REVIVE), 루비 미차감`() {
        inst(streak = 3, lastProgress = today.minusDays(2).atStartOfDay())
        val item = service.revive("u", "cat", GrowthReviveRequest.Method.AD, "550e8400-e29b-41d4-a716-446655440000")
        verify(rewardService).consumeAdNonce(eq("u"), eq("550e8400-e29b-41d4-a716-446655440000"), eq(AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE))
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertEquals(GrowthItem.CycleState.ACTIVE, item.cycleState)
    }

    @Test
    fun `revive — LOST 가 아니면 차감 없이 현재 상태 반환 (멱등)`() {
        inst(streak = 3, lastProgress = today.atStartOfDay())
        val item = service.revive("u", "cat", GrowthReviveRequest.Method.RUBY, null)
        assertEquals(GrowthItem.CycleState.ACTIVE, item.cycleState)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `revive — 미존재 종은 GROWTH_SPECIES_NOT_FOUND`() {
        assertEquals(ErrorCode.GROWTH_SPECIES_NOT_FOUND, assertThrows<BusinessException> { service.revive("u", "nope", GrowthReviveRequest.Method.RUBY, null) }.errorCode)
    }

    @Test
    fun `revive-dismiss — 보류 설정 후 당일 revive 는 GROWTH_REVIVE_SNOOZED, 다음날 새 사이클 리셋(진행 0, cycleId 변경)`() {
        val i = inst(streak = 8, lastProgress = today.minusDays(2).atStartOfDay())
        val before = i.cycleId
        val dismissed = service.dismissRevive("u", "cat")
        assertEquals(today.plusDays(1), dismissed.reviveSnoozedUntil)
        assertEquals(ErrorCode.GROWTH_REVIVE_SNOOZED, assertThrows<BusinessException> { service.revive("u", "cat", GrowthReviveRequest.Method.RUBY, null) }.errorCode)

        // 다음날 (스케줄러 경로) — 새 사이클
        assertTrue(service.resetDueById(1L, today.plusDays(1)))
        val after = instanceRepo.findById(1L).get()
        assertEquals(GrowthCycleState.ACTIVE, after.cycleState)
        assertEquals(0, after.effectiveProgress)
        assertNotEquals(before, after.cycleId)
        assertNull(after.reviveSnoozedUntilKstDate)
        verify(eventPublisher, never()).publishEvent(any<SpiritArrivedEvent>())
    }

    @Test
    fun `완료 리셋 — 다음날 조회 시 새 사이클 ACTIVE 진행 0 + notifyNext 면 SPIRIT_ARRIVED 1회 + 해제`() {
        inst(streak = 29, lastProgress = today.minusDays(1).atStartOfDay())
        service.toggleNotifyNext("u", "cat")
        service.advanceAllStreaks("u") // 30 도달로 완료 전이
        val before = instanceRepo.findById(1L).get().cycleId
        // 오늘은 완료 유지
        assertEquals(GrowthItem.CycleState.COMPLETED, cat().cycleState)
        assertTrue(cat().notifyNext)

        // 기한 도래(내일) — 스케줄러 경로로 1회 전환, 조회 경로 재시도는 no-op
        assertTrue(service.resetDueById(1L, today.plusDays(1)))
        assertFalse(service.resetDueById(1L, today.plusDays(1)))
        val after = instanceRepo.findById(1L).get()
        assertEquals(GrowthCycleState.ACTIVE, after.cycleState)
        assertEquals(0, after.effectiveProgress)
        assertNotEquals(before, after.cycleId)
        assertFalse(after.notifyNext)
        verify(eventPublisher, times(1)).publishEvent(any<SpiritArrivedEvent>())
    }

    @Test
    fun `toggleNotifyNext — 개체 미생성이면 생성하며 true, 재호출 false`() {
        assertTrue(service.toggleNotifyNext("u", "cat").notifyNext)
        assertFalse(service.toggleNotifyNext("u", "cat").notifyNext)
    }

    // ─── 페이크 ─────────────────────────────────────────────────

    private class FakeSpeciesRepository :
        FakeJpaRepository<GrowthSpecies, String>(),
        GrowthSpeciesRepository {
        override fun extractId(entity: GrowthSpecies): String = entity.code

        override fun findAllByOrderBySortOrderAsc(): List<GrowthSpecies> = store.values.sortedBy { it.sortOrder }
    }

    private class FakeStageRepository :
        FakeJpaRepository<GrowthStage, GrowthStageId>(),
        GrowthStageRepository {
        override fun extractId(entity: GrowthStage): GrowthStageId = GrowthStageId(entity.kind, entity.stageOrder)

        override fun findAllByKindOrderByStageOrderAsc(kind: String): List<GrowthStage> = store.values.filter { it.kind == kind }.sortedBy { it.stageOrder }
    }

    private class FakeInstanceRepository :
        FakeJpaRepository<GrowthInstance, Long>(),
        GrowthInstanceRepository {
        private var seq = 100L

        override fun extractId(entity: GrowthInstance): Long = entity.id

        override fun assignId(entity: GrowthInstance): GrowthInstance {
            if (entity.id != 0L) return entity
            val idField = GrowthInstance::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, seq++)
            return entity
        }

        override fun findAllByUserId(userId: String): List<GrowthInstance> = store.values.filter { it.userId == userId }

        override fun findByUserIdAndSpeciesCode(
            userId: String,
            speciesCode: String,
        ): GrowthInstance? = store.values.firstOrNull { it.userId == userId && it.speciesCode == speciesCode }

        /** markLost 직전에 실행되는 훅 — "판정 read 와 CAS 사이에 다른 tx 가 기록을 커밋" 하는 상황을 흉내낸다. */
        var beforeMarkLost: ((GrowthInstance) -> Unit)? = null

        override fun markLost(
            id: Long,
            now: LocalDateTime,
            observedLastProgressAt: LocalDateTime,
        ): Int {
            store[id]?.let { beforeMarkLost?.invoke(it) }
            val g = store[id]?.takeIf { it.cycleState == GrowthCycleState.ACTIVE && it.lastProgressAt == observedLastProgressAt } ?: return 0
            g.cycleState = GrowthCycleState.LOST
            g.lostAt = now
            g.version += 1
            return 1
        }

        private fun resetTo(
            g: GrowthInstance,
            now: LocalDateTime,
            newCycleId: UUID,
        ) {
            g.cycleState = GrowthCycleState.ACTIVE
            g.cycleId = newCycleId
            g.naturalStreak = 0
            g.sparkleBoughtCount = 0
            g.currentStage = 1
            g.lastProgressAt = null
            g.completedKstDate = null
            g.completedAt = null
            g.resetDueKstDate = null
            g.resetProcessedAt = now
            g.reviveSnoozedUntilKstDate = null
            g.lostAt = null
            g.version += 1
        }

        override fun resetCompletedCycle(
            id: Long,
            today: LocalDate,
            now: LocalDateTime,
            newCycleId: UUID,
        ): Int {
            val g =
                store[id]?.takeIf {
                    it.cycleState == GrowthCycleState.COMPLETED &&
                        it.resetProcessedAt == null &&
                        it.resetDueKstDate?.let { d -> !d.isAfter(today) } == true
                } ?: return 0
            resetTo(g, now, newCycleId)
            g.notifyNext = false
            return 1
        }

        override fun resetSnoozedCycle(
            id: Long,
            today: LocalDate,
            now: LocalDateTime,
            newCycleId: UUID,
        ): Int {
            val g =
                store[id]?.takeIf {
                    it.cycleState == GrowthCycleState.LOST && it.reviveSnoozedUntilKstDate?.let { d -> !d.isAfter(today) } == true
                } ?: return 0
            resetTo(g, now, newCycleId)
            return 1
        }

        override fun findCompletedResetDueIds(today: LocalDate): List<Long> =
            store.values
                .filter { it.cycleState == GrowthCycleState.COMPLETED && it.resetProcessedAt == null && it.resetDueKstDate?.let { d -> !d.isAfter(today) } == true }
                .map { it.id }

        override fun findSnoozeExpiredIds(today: LocalDate): List<Long> =
            store.values
                .filter { it.cycleState == GrowthCycleState.LOST && it.reviveSnoozedUntilKstDate?.let { d -> !d.isAfter(today) } == true }
                .map { it.id }
    }
}
