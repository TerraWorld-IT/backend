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
import kotlin.test.assertNull

/**
 * TierService.unlockTier 커버 (낙서장 P2).
 * - happy GLASS→LARGE (SPARKLE만), GRAND (SPARKLE+RUBY + 비둘기 정령 지급)
 * - 비순차 해금 INVALID_INPUT (차감 없음)
 * - INSUFFICIENT_FUNDS 전파
 */
class TierServiceTest {
    private val terrariumRepo = mock(TerrariumRepository::class.java)
    private val tierConfigRepo = mock(TierConfigRepository::class.java)
    private val currencyService = mock(CurrencyService::class.java)
    private val grantService = mock(GrantService::class.java)
    private val service = TierService(terrariumRepo, tierConfigRepo, currencyService, grantService)

    private val glass = TierConfig("GLASS_JAR", 1, "유리병", 0, 0, 6, null)
    private val large = TierConfig("LARGE_JAR", 2, "큰유리병", 240, 0, 10, null)
    private val grand = TierConfig("GRAND_TANK", 3, "대형", 720, 60, 16, "pigeon")

    private fun terrarium(tier: String) =
        Terrarium(
            user = mock(User::class.java),
            background = mock(TerrariumBackground::class.java),
            tier = tier,
        )

    @BeforeEach
    fun setup() {
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))
    }

    @Test
    fun `GLASS to LARGE - SPARKLE 240 차감, RUBY 미차감, 정령 없음`() {
        val terr = terrarium("GLASS_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(tierConfigRepo.findById("LARGE_JAR")).thenReturn(Optional.of(large))
        whenever(tierConfigRepo.findById("GLASS_JAR")).thenReturn(Optional.of(glass))
        whenever(terrariumRepo.casTier(any(), eq("GLASS_JAR"), eq("LARGE_JAR"), any())).thenReturn(1)

        val r = service.unlockTier("u", "LARGE_JAR")

        assertEquals("LARGE_JAR", r.tier)
        assertEquals(10, r.slots)
        assertNull(r.grantedSpirit)
        // M5: CAS 로 원자 상승 — 서비스가 in-memory terr.tier 를 직접 변경하지 않고 조건부 UPDATE 로 상승.
        verify(terrariumRepo).casTier(any(), eq("GLASS_JAR"), eq("LARGE_JAR"), any())
        verify(currencyService).debit(eq("u"), eq("SPARKLE"), eq(240L), any(), anyOrNull(), anyOrNull())
        verify(currencyService, never()).debit(eq("u"), eq("RUBY"), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `LARGE to GRAND - SPARKLE 720 + RUBY 60 차감 + 비둘기 지급`() {
        val terr = terrarium("LARGE_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(tierConfigRepo.findById("GRAND_TANK")).thenReturn(Optional.of(grand))
        whenever(tierConfigRepo.findById("LARGE_JAR")).thenReturn(Optional.of(large))
        whenever(grantService.grant(any(), eq(GrantType.SPIRIT), eq("pigeon"), any(), any(), any())).thenReturn(true)
        whenever(terrariumRepo.casTier(any(), eq("LARGE_JAR"), eq("GRAND_TANK"), any())).thenReturn(1)

        val r = service.unlockTier("u", "GRAND_TANK")

        assertEquals("pigeon", r.grantedSpirit)
        verify(currencyService).debit(eq("u"), eq("SPARKLE"), eq(720L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).debit(eq("u"), eq("RUBY"), eq(60L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `비순차 GLASS to GRAND 는 INVALID_INPUT + 차감 없음`() {
        val terr = terrarium("GLASS_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(tierConfigRepo.findById("GRAND_TANK")).thenReturn(Optional.of(grand))
        whenever(tierConfigRepo.findById("GLASS_JAR")).thenReturn(Optional.of(glass))

        val ex = assertThrows<BusinessException> { service.unlockTier("u", "GRAND_TANK") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        verify(currencyService, never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `잔액 부족은 INSUFFICIENT_FUNDS 전파`() {
        val terr = terrarium("GLASS_JAR")
        whenever(terrariumRepo.findByUserId("u")).thenReturn(Optional.of(terr))
        whenever(tierConfigRepo.findById("LARGE_JAR")).thenReturn(Optional.of(large))
        whenever(tierConfigRepo.findById("GLASS_JAR")).thenReturn(Optional.of(glass))
        whenever(currencyService.debit(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))

        val ex = assertThrows<BusinessException> { service.unlockTier("u", "LARGE_JAR") }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }
}
