package com.terraworld.api.attendance

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.attendance.AttendanceLog
import com.terraworld.domain.attendance.AttendanceLogRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
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
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AttendanceService 분기 커버 (낙서장 P1 read-cutover 후: 보상 = CurrencyService.credit 단일 SoT).
 *  - checkIn 해피: streak + 기본 보상 5 (credit COIN 5 verify)
 *  - 7일 보너스: streak 6→7 시 20 (credit COIN 20)
 *  - 중복 체크인 ATTENDANCE_ALREADY_CHECKED (credit 없음)
 *  - 사용자 미존재 USER_NOT_FOUND / getState streak 분기
 */
class AttendanceServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var attendanceRepo: FakeAttendanceLogRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var service: AttendanceService

    private val today: LocalDate = KstTime.today()
    private val yesterday: LocalDate = today.minusDays(1)

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        attendanceRepo = FakeAttendanceLogRepository()
        currencyService = mock(CurrencyService::class.java)
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))

        val walletBuilder = WalletBuilder(currencyService)
        val auditService = mock(AuditService::class.java)
        val walletTransactionService = mock(com.terraworld.api.wallet.WalletTransactionService::class.java)

        service =
            AttendanceService(
                userRepo,
                currencyService,
                attendanceRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
            )

        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    @Test
    fun `checkIn 해피 패스 — 첫 출석 streak 1 + COIN 5 credit`() {
        val response = service.checkIn("user-1")

        assertEquals(1, response.attendance.streak)
        assertEquals(5, response.reward.basicCoins)
        assertFalse(response.reward.bonus)
        assertTrue(response.attendance.today)
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(5L), any(), anyOrNull(), anyOrNull())

        val log = attendanceRepo.findByUserIdAndCheckInDate("user-1", today)
        assertTrue(log.isPresent)
        assertEquals(1, log.get().streak)
    }

    @Test
    fun `checkIn — 어제 streak 3 이면 오늘 streak 4`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = yesterday, streak = 3, rewardBasicCoins = 5, bonus = false))
        val response = service.checkIn("user-1")
        assertEquals(4, response.attendance.streak)
        assertEquals(5, response.reward.basicCoins)
        assertFalse(response.reward.bonus)
    }

    @Test
    fun `checkIn — 어제 streak 6 이면 오늘 streak 7 보너스 COIN 20`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = yesterday, streak = 6, rewardBasicCoins = 5, bonus = false))
        val response = service.checkIn("user-1")
        assertEquals(7, response.attendance.streak)
        assertEquals(20, response.reward.basicCoins)
        assertTrue(response.reward.bonus)
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(20L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn — 오늘 이미 체크인했으면 ATTENDANCE_ALREADY_CHECKED (credit 없음)`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = today, streak = 1, rewardBasicCoins = 5, bonus = false))
        val ex = assertThrows<BusinessException> { service.checkIn("user-1") }
        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.errorCode)
        verify(currencyService, never()).credit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex = assertThrows<BusinessException> { service.checkIn("ghost") }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `getState — 어제 로그 없이 과거 로그만이면 streak 단절 nextStreak 1`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = today.minusDays(2), streak = 5, rewardBasicCoins = 5, bonus = false))
        val state = service.getState("user-1")
        assertFalse(state.today)
        assertEquals(0, state.streak)
        assertEquals(5, state.longestStreak)
        assertEquals(5, state.rewardBasicCoins)
        assertEquals(false, state.bonusEligible)
    }

    @Test
    fun `getState — 오늘 이미 체크인했으면 today=true nextStreak 미증가`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = today, streak = 3, rewardBasicCoins = 5, bonus = false))
        val state = service.getState("user-1")
        assertTrue(state.today)
        assertEquals(3, state.streak)
        assertEquals(false, state.bonusEligible)
        assertEquals(5, state.rewardBasicCoins)
    }

    @Test
    fun `getState — 어제 streak 6 이면 nextStreak 7 보너스 자격 20`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = yesterday, streak = 6, rewardBasicCoins = 5, bonus = false))
        val state = service.getState("user-1")
        assertFalse(state.today)
        assertEquals(6, state.streak)
        assertEquals(true, state.bonusEligible)
        assertEquals(20, state.rewardBasicCoins)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id

        override fun acquireBootstrapLock(key: String): Int = 1
    }

    private class FakeAttendanceLogRepository :
        FakeJpaRepository<AttendanceLog, Long>(),
        AttendanceLogRepository {
        private var seq = 1L

        override fun extractId(entity: AttendanceLog): Long = entity.id

        override fun assignId(entity: AttendanceLog): AttendanceLog =
            if (entity.id == 0L) {
                AttendanceLog(
                    id = seq++,
                    userId = entity.userId,
                    checkInDate = entity.checkInDate,
                    streak = entity.streak,
                    rewardBasicCoins = entity.rewardBasicCoins,
                    bonus = entity.bonus,
                    createdAt = entity.createdAt,
                )
            } else {
                entity
            }

        override fun findByUserIdAndCheckInDate(
            userId: String,
            checkInDate: LocalDate,
        ): Optional<AttendanceLog> =
            Optional.ofNullable(
                store.values.firstOrNull { it.userId == userId && it.checkInDate == checkInDate },
            )

        override fun findLatestByUserId(userId: String): Optional<AttendanceLog> =
            Optional.ofNullable(
                store.values.filter { it.userId == userId }.maxByOrNull { it.checkInDate },
            )

        override fun findLongestStreak(userId: String): Int = store.values.filter { it.userId == userId }.maxOfOrNull { it.streak } ?: 0
    }
}
