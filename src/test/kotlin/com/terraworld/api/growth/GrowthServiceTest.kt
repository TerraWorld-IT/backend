package com.terraworld.api.growth

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.growth.GrowthInstance
import com.terraworld.domain.growth.GrowthInstanceRepository
import com.terraworld.domain.growth.GrowthSpecies
import com.terraworld.domain.growth.GrowthSpeciesRepository
import com.terraworld.domain.growth.GrowthStage
import com.terraworld.domain.growth.GrowthStageId
import com.terraworld.domain.growth.GrowthStageRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * GrowthService mutation 커버 (낙서장 P3). 단일 종(cat SPIRIT)으로 id 충돌 회피.
 * - advanceAllStreaks: 최초 생성(streak 1) / 연속(streak++) / 공백 실패(streak 1 재시작, 반짝이 유지) / 같은날 멱등
 * - buyBooster: SPARKLE 100 debit + sparkleBoughtCount +10 + stage 재계산
 * - dormant: 마지막 진행 3일+ → 동면
 */
class GrowthServiceTest {
    private lateinit var speciesRepo: FakeSpeciesRepository
    private lateinit var stageRepo: FakeStageRepository
    private lateinit var instanceRepo: FakeInstanceRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var service: GrowthService
    private val today = KstTime.today()

    @BeforeEach
    fun setup() {
        speciesRepo = FakeSpeciesRepository()
        stageRepo = FakeStageRepository()
        instanceRepo = FakeInstanceRepository()
        currencyService = mock(CurrencyService::class.java)
        service = GrowthService(speciesRepo, stageRepo, instanceRepo, currencyService)

        speciesRepo.save(GrowthSpecies(code = "cat", kind = "SPIRIT", nameKo = "고양이 정령", sortOrder = 1))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 1, threshold = 1, labelKo = "알"))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 2, threshold = 15, labelKo = "깨진 알"))
        stageRepo.save(GrowthStage(kind = "SPIRIT", stageOrder = 3, threshold = 30, labelKo = "정령"))
    }

    @Test
    fun `advanceAllStreaks 최초 — 개체 생성 streak 1`() {
        service.advanceAllStreaks("u")
        val inst = instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!
        assertEquals(1, inst.naturalStreak)
        assertEquals(1, inst.currentStage)
    }

    @Test
    fun `advanceAllStreaks 연속 — 어제 진행이면 streak++`() {
        instanceRepo.save(
            GrowthInstance(id = 1L, userId = "u", speciesCode = "cat", naturalStreak = 5, lastProgressAt = today.minusDays(1).atStartOfDay()),
        )
        service.advanceAllStreaks("u")
        assertEquals(6, instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!.naturalStreak)
    }

    @Test
    fun `advanceAllStreaks 공백 실패 — streak 1 재시작 + 반짝이 유지`() {
        instanceRepo.save(
            GrowthInstance(
                id = 1L,
                userId = "u",
                speciesCode = "cat",
                naturalStreak = 10,
                sparkleBoughtCount = 20,
                lastProgressAt = today.minusDays(3).atStartOfDay(),
            ),
        )
        service.advanceAllStreaks("u")
        val inst = instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!
        assertEquals(1, inst.naturalStreak)
        assertEquals(20, inst.sparkleBoughtCount) // 실패해도 반짝이 유지 (§3-8)
    }

    @Test
    fun `advanceAllStreaks 같은날 — 멱등 no-op`() {
        instanceRepo.save(
            GrowthInstance(id = 1L, userId = "u", speciesCode = "cat", naturalStreak = 3, lastProgressAt = today.atStartOfDay()),
        )
        service.advanceAllStreaks("u")
        assertEquals(3, instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!.naturalStreak)
    }

    @Test
    fun `buyBooster — SPARKLE 100 debit + sparkleBoughtCount +10 + stage 재계산`() {
        instanceRepo.save(GrowthInstance(id = 1L, userId = "u", speciesCode = "cat", naturalStreak = 6))
        val item = service.buyBooster("u", "cat")
        verify(currencyService).debit(eq("u"), eq("SPARKLE"), eq(100L), any(), anyOrNull(), anyOrNull())
        val inst = instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!
        assertEquals(10, inst.sparkleBoughtCount)
        // 유효 진행도 = 6 + 10 = 16 → stage 2 (임계 15)
        assertEquals(2, item.currentStage)
        assertEquals(16, item.effectiveProgress)
    }

    @Test
    fun `buyBooster 미존재 종 — INVALID_INPUT`() {
        val ex = assertThrows<BusinessException> { service.buyBooster("u", "unknown") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `buyBooster 동면 — GROWTH_DORMANT 이고 SPARKLE 미차감`() {
        instanceRepo.save(
            GrowthInstance(
                id = 1L,
                userId = "u",
                speciesCode = "cat",
                naturalStreak = 5,
                lastProgressAt = today.minusDays(4).atStartOfDay(),
            ),
        )
        val ex = assertThrows<BusinessException> { service.buyBooster("u", "cat") }
        assertEquals(ErrorCode.GROWTH_DORMANT, ex.errorCode)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertEquals(0, instanceRepo.findByUserIdAndSpeciesCode("u", "cat")!!.sparkleBoughtCount)
    }

    @Test
    fun `getGrowth dormant — 마지막 진행 4일 전이면 동면`() {
        instanceRepo.save(
            GrowthInstance(id = 1L, userId = "u", speciesCode = "cat", naturalStreak = 5, lastProgressAt = today.minusDays(4).atStartOfDay()),
        )
        val item = service.getGrowth("u").items.first { it.speciesCode == "cat" }
        assertTrue(item.dormant)
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
        override fun extractId(entity: GrowthInstance): Long = entity.id

        override fun findAllByUserId(userId: String): List<GrowthInstance> = store.values.filter { it.userId == userId }

        override fun findByUserIdAndSpeciesCode(
            userId: String,
            speciesCode: String,
        ): GrowthInstance? = store.values.firstOrNull { it.userId == userId && it.speciesCode == speciesCode }
    }
}
