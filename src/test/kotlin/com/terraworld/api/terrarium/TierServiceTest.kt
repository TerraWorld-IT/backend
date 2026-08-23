package com.terraworld.api.terrarium

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.TierConfig
import com.terraworld.domain.terrarium.TierConfigRepository
import com.terraworld.domain.user.User
import io.terraworld.api.model.CurrencyResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TierService 아프젝 v2 커버 (§R1, 3레벨 루비 전용).
 * - happy GLASS→LARGE (RUBY 30), LARGE→GRAND (RUBY 50 + 비둘기 정령 ITEM/SPIRIT 지급) — active_tier 는 불변
 * - 비순차 해금 INVALID_INPUT (차감 없음) / 비활성(HOUSE_TANK) INVALID_INPUT / INSUFFICIENT_FUNDS 전파
 * - 카탈로그: 활성 3종만, level/descriptionKo/unlocked/active, activeTier·highestUnlockedTier 분리
 * - 카탈로그: HOUSE_TANK(비활성)에 이미 도달한 계정에는 HOUSE_TANK 가 포함된다 (FE 가 그 병을 그릴 수 있게)
 */
class TierServiceTest {
    private val terrariumRepo = mock(TerrariumRepository::class.java)
    private val tierConfigRepo = mock(TierConfigRepository::class.java)
    private val currencyService = mock(CurrencyService::class.java)
    private val grantService = mock(GrantService::class.java)
    private val service = TierService(terrariumRepo, tierConfigRepo, currencyService, grantService)

    private val glass = TierConfig("GLASS_JAR", 1, "유리병", 0, 0, 10, null, level = 1, descriptionKo = "언덕과 바위가 있는 오픈형 테라리움 입니다")
    private val large = TierConfig("LARGE_JAR", 2, "큰유리병", 0, 30, 20, null, level = 2, descriptionKo = "더 넓은 병")
    private val grand = TierConfig("GRAND_TANK", 3, "대형", 0, 50, 40, "pigeon", level = 3, descriptionKo = "정령이 함께")
    private val house = TierConfig("HOUSE_TANK", 4, "하우스형", 1600, 180, 24, "fish", level = 4, isActive = false)

    private fun terrarium(
        tier: String,
        activeTier: String = tier,
    ) = Terrarium(
        user = mock(User::class.java),
        background = mock(TerrariumBackground::class.java),
        tier = tier,
        activeTier = activeTier,
    )

    @BeforeEach
    fun setup() {
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))
        whenever(tierConfigRepo.findById("GLASS_JAR")).thenReturn(Optional.of(glass))
        whenever(tierConfigRepo.findById("LARGE_JAR")).thenReturn(Optional.of(large))
        whenever(tierConfigRepo.findById("GRAND_TANK")).thenReturn(Optional.of(grand))
        whenever(tierConfigRepo.findById("HOUSE_TANK")).thenReturn(Optional.of(house))
        whenever(tierConfigRepo.findAllByIsActiveTrueOrderByTierOrderAsc()).thenReturn(listOf(glass, large, grand))
        whenever(tierConfigRepo.findAllByOrderByTierOrderAsc()).thenReturn(listOf(glass, large, grand, house))
        whenever(grantService.grant(any(), any(), any(), any(), any(), any())).thenReturn(true)
    }

    @Test
    fun `GLASS to LARGE - RUBY 30 차감, SPARKLE 미차감, 정령 없음, activeTier 불변`() {
        val terr = terrarium("GLASS_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(terrariumRepo.casTier(any(), eq("GLASS_JAR"), eq("LARGE_JAR"), any())).thenReturn(1)

        val r = service.unlockTier("u", "LARGE_JAR")

        assertEquals("LARGE_JAR", r.tier)
        assertEquals(20, r.slots)
        assertNull(r.grantedSpirit)
        verify(terrariumRepo).casTier(any(), eq("GLASS_JAR"), eq("LARGE_JAR"), any())
        verify(currencyService).debit(eq("u"), eq("RUBY"), eq(30L), any(), anyOrNull(), anyOrNull())
        verify(currencyService, never()).debit(eq("u"), eq("SPARKLE"), any(), any(), anyOrNull(), anyOrNull())
        // 해금은 표시 중 병을 바꾸지 않는다 (casTier 는 tier 컬럼만)
        assertEquals("GLASS_JAR", terr.activeTier)
    }

    @Test
    fun `LARGE to GRAND - RUBY 50 차감 + 비둘기 정령 ITEM(pigeon-spirit) + SPIRIT 지급`() {
        val terr = terrarium("LARGE_JAR", activeTier = "GLASS_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(terrariumRepo.casTier(any(), eq("LARGE_JAR"), eq("GRAND_TANK"), any())).thenReturn(1)

        val r = service.unlockTier("u", "GRAND_TANK")

        assertEquals("pigeon", r.grantedSpirit)
        verify(currencyService).debit(eq("u"), eq("RUBY"), eq(50L), any(), anyOrNull(), anyOrNull())
        verify(grantService).grant(eq("u"), eq(GrantType.ITEM), eq("pigeon-spirit"), eq(1L), eq("tier-GRAND_TANK:item"), any())
        verify(grantService).grant(eq("u"), eq(GrantType.SPIRIT), eq("pigeon"), eq(1L), eq("tier-GRAND_TANK"), any())
    }

    @Test
    fun `이미 보유한 정령이면 grantedSpirit null (멱등)`() {
        val terr = terrarium("LARGE_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(terrariumRepo.casTier(any(), eq("LARGE_JAR"), eq("GRAND_TANK"), any())).thenReturn(1)
        whenever(grantService.grant(any(), eq(GrantType.ITEM), any(), any(), any(), any())).thenReturn(false)
        assertNull(service.unlockTier("u", "GRAND_TANK").grantedSpirit)
    }

    @Test
    fun `비순차 GLASS to GRAND 는 INVALID_INPUT + 차감 없음`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("GLASS_JAR")))
        val ex = assertThrows<BusinessException> { service.unlockTier("u", "GRAND_TANK") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `비활성 티어(HOUSE_TANK) 해금은 INVALID_INPUT`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("GRAND_TANK")))
        val ex = assertThrows<BusinessException> { service.unlockTier("u", "HOUSE_TANK") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `잔액 부족은 INSUFFICIENT_FUNDS 전파`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("GLASS_JAR")))
        whenever(currencyService.debit(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))
        val ex = assertThrows<BusinessException> { service.unlockTier("u", "LARGE_JAR") }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `CAS 패자(rows 0)는 CONCURRENT_MODIFICATION`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("GLASS_JAR")))
        whenever(terrariumRepo.casTier(any(), any(), any(), any())).thenReturn(0)
        val ex = assertThrows<BusinessException> { service.unlockTier("u", "LARGE_JAR") }
        assertEquals(ErrorCode.CONCURRENT_MODIFICATION, ex.errorCode)
    }

    @Test
    fun `카탈로그 — 활성 3종, level·descriptionKo·unlocked·active, activeTier 와 highestUnlockedTier 분리`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("LARGE_JAR", activeTier = "GLASS_JAR")))
        val c = service.getCatalog("u")
        assertEquals("LARGE_JAR", c.currentTier)
        assertEquals("LARGE_JAR", c.highestUnlockedTier)
        assertEquals("GLASS_JAR", c.activeTier)
        assertEquals(listOf("GLASS_JAR", "LARGE_JAR", "GRAND_TANK"), c.tiers.map { it.tier })
        assertEquals(listOf(true, true, false), c.tiers.map { it.unlocked })
        assertEquals(listOf(true, false, false), c.tiers.map { it.active })
        assertEquals(2, c.tiers[1].level)
        assertEquals("언덕과 바위가 있는 오픈형 테라리움 입니다", c.tiers[0].descriptionKo)
        assertEquals(50L, c.tiers[2].rubyCost)
        assertEquals(0L, c.tiers[2].sparkleCost)
        assertEquals(40, c.tiers[2].slots)
        assertTrue(c.tiers.none { it.tier == "HOUSE_TANK" })
        assertFalse(c.tiers[2].active)
    }

    @Test
    fun `카탈로그 — HOUSE_TANK 에 이미 도달한 계정은 비활성 HOUSE_TANK 도 포함(unlocked, active)`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("HOUSE_TANK", activeTier = "HOUSE_TANK")))
        val c = service.getCatalog("u")
        assertEquals("HOUSE_TANK", c.highestUnlockedTier)
        assertEquals(listOf("GLASS_JAR", "LARGE_JAR", "GRAND_TANK", "HOUSE_TANK"), c.tiers.map { it.tier })
        assertEquals(listOf(true, true, true, true), c.tiers.map { it.unlocked })
        assertTrue(c.tiers[3].active)
        assertEquals(24, c.tiers[3].slots)
    }

    @Test
    fun `카탈로그 — GRAND_TANK 계정에는 HOUSE_TANK 가 여전히 노출되지 않는다`() {
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terrarium("GRAND_TANK")))
        val c = service.getCatalog("u")
        assertEquals(listOf("GLASS_JAR", "LARGE_JAR", "GRAND_TANK"), c.tiers.map { it.tier })
    }
}
