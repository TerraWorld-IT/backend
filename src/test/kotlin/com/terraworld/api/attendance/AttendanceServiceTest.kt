package com.terraworld.api.attendance

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.notification.AttendanceCycleCompletedEvent
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
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AttendanceService 아프젝 v2 7일 보드 커버 (§R4). 보상 = CurrencyService.credit 단일 SoT.
 *  - checkIn: 1일차 COIN 10 / 4일차 40 / 7일차 70 + RUBY 5 (rubyBonus) / 8일차 = 새 사이클 1일차 10
 *  - 7일차 중복 POST 409 ATTENDANCE_ALREADY_CHECKED (credit 없음)
 *  - D+1 누락: streak 0 → cycleDay 1, 보드 초기화(전부 미수령), cycleStart null
 *  - getState: cycleDay 규칙(체크인 전 = 다음 일차 / 후 = 오늘 일차), board claimed/claimedAt, cycleStartDateKst, serverDateKst
 *  - KST 자정 경계: 어제(KST) 로그만 있으면 오늘은 미체크 + streak 유지
 */
class AttendanceServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var attendanceRepo: FakeAttendanceLogRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var eventPublisher: ApplicationEventPublisher
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
        eventPublisher = mock(ApplicationEventPublisher::class.java)

        service =
            AttendanceService(
                userRepo,
                currencyService,
                attendanceRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
                eventPublisher,
                AttendanceProperties(),
            )

        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    /** 어제까지 streak 일 연속 출석 로그 시드 (보드 파생 검증용). */
    private fun seedStreakUntilYesterday(streak: Int) {
        for (i in 0 until streak) {
            val date = yesterday.minusDays((streak - 1 - i).toLong())
            val s = i + 1
            val day = ((s - 1) % 7) + 1
            attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = date, streak = s, rewardBasicCoins = day * 10, bonus = day == 7))
        }
    }

    @Test
    fun `checkIn 첫 출석 — streak 1, cycleDay 1, COIN 10, 루비 없음, 보드 1칸 수령`() {
        val r = service.checkIn("user-1")
        assertEquals(1, r.attendance.streak)
        assertEquals(1, r.attendance.cycleDay)
        assertEquals(10, r.reward.basicCoins)
        assertEquals(0, r.reward.rubyBonus)
        assertFalse(r.reward.bonus)
        assertTrue(r.attendance.today)
        assertEquals(today, r.attendance.serverDateKst)
        assertEquals(today, r.attendance.cycleStartDateKst)
        assertTrue(r.attendance.board[0].claimed)
        assertTrue(r.attendance.board[0].claimedAt != null)
        assertFalse(r.attendance.board[1].claimed)
        assertEquals(10, r.attendance.rewardBasicCoins) // 스펙: 오늘 이미 수령했으면 오늘 일차(1일차)의 지급액
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(10L), any(), anyOrNull(), anyOrNull())
        verify(currencyService, never()).credit(eq("user-1"), eq("RUBY"), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn — 어제 streak 3 이면 오늘 4일차 COIN 40`() {
        seedStreakUntilYesterday(3)
        val r = service.checkIn("user-1")
        assertEquals(4, r.attendance.streak)
        assertEquals(4, r.attendance.cycleDay)
        assertEquals(40, r.reward.basicCoins)
        assertEquals(yesterday.minusDays(2), r.attendance.cycleStartDateKst)
        assertEquals(listOf(true, true, true, true, false, false, false), r.attendance.board.map { it.claimed })
    }

    @Test
    fun `checkIn 7일차 — COIN 70 + RUBY 5 (rubyBonus), bonus true, cycleBonusClaimed`() {
        seedStreakUntilYesterday(6)
        val r = service.checkIn("user-1")
        assertEquals(7, r.attendance.streak)
        assertEquals(7, r.attendance.cycleDay)
        assertEquals(70, r.reward.basicCoins)
        assertEquals(5, r.reward.rubyBonus)
        assertTrue(r.reward.bonus)
        assertTrue(r.attendance.cycleBonusClaimed)
        assertTrue(r.attendance.board.all { it.claimed })
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(70L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("RUBY"), eq(5L), any(), anyOrNull(), anyOrNull())
        verify(eventPublisher).publishEvent(AttendanceCycleCompletedEvent("user-1"))
    }

    @Test
    fun `checkIn 7일차 중복 POST — ATTENDANCE_ALREADY_CHECKED (credit 없음)`() {
        seedStreakUntilYesterday(6)
        service.checkIn("user-1")
        org.mockito.Mockito.clearInvocations(currencyService)
        val ex = assertThrows<BusinessException> { service.checkIn("user-1") }
        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.errorCode)
        verify(currencyService, never()).credit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn 8일차 — streak 8 은 새 사이클 1일차 COIN 10, 보드 1칸`() {
        seedStreakUntilYesterday(7)
        val r = service.checkIn("user-1")
        assertEquals(8, r.attendance.streak)
        assertEquals(1, r.attendance.cycleDay)
        assertEquals(10, r.reward.basicCoins)
        assertEquals(0, r.reward.rubyBonus)
        assertEquals(today, r.attendance.cycleStartDateKst)
        assertEquals(listOf(true, false, false, false, false, false, false), r.attendance.board.map { it.claimed })
    }

    @Test
    fun `D+1 누락 — 그제까지 streak 5 였어도 오늘은 streak 0 → cycleDay 1, 보드 초기화, cycleStart null`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = today.minusDays(2), streak = 5, rewardBasicCoins = 50, bonus = false))
        val state = service.getState("user-1")
        assertFalse(state.today)
        assertEquals(0, state.streak)
        assertEquals(1, state.cycleDay)
        assertNull(state.cycleStartDateKst)
        assertTrue(state.board.none { it.claimed })
        assertEquals(10, state.rewardBasicCoins)
        assertEquals(5, state.longestStreak)
        assertEquals(false, state.bonusEligible)

        val r = service.checkIn("user-1")
        assertEquals(1, r.attendance.streak)
        assertEquals(10, r.reward.basicCoins)
    }

    @Test
    fun `getState 체크인 전 — 어제 streak 3 이면 cycleDay 4 (다음 일차), 보드 3칸 수령, 40 미리보기`() {
        seedStreakUntilYesterday(3)
        val state = service.getState("user-1")
        assertFalse(state.today)
        assertEquals(3, state.streak)
        assertEquals(4, state.cycleDay)
        assertEquals(40, state.rewardBasicCoins)
        assertEquals(yesterday.minusDays(2), state.cycleStartDateKst)
        assertEquals(listOf(true, true, true, false, false, false, false), state.board.map { it.claimed })
        assertEquals(5, state.cycleBonusRuby)
        assertFalse(state.cycleBonusClaimed)
    }

    @Test
    fun `getState 체크인 후 — streak 7 today=true 면 cycleDay 7, cycleBonusClaimed true`() {
        seedStreakUntilYesterday(6)
        service.checkIn("user-1")
        val state = service.getState("user-1")
        assertTrue(state.today)
        assertEquals(7, state.cycleDay)
        assertTrue(state.cycleBonusClaimed)
        assertEquals(true, state.bonusEligible)
    }

    @Test
    fun `getState — 어제 7일차 완료 후 오늘 미체크면 cycleDay 1, 새 사이클이라 보드 비어 있음`() {
        seedStreakUntilYesterday(7)
        val state = service.getState("user-1")
        assertEquals(7, state.streak)
        assertEquals(1, state.cycleDay)
        assertNull(state.cycleStartDateKst)
        assertTrue(state.board.none { it.claimed })
        assertFalse(state.cycleBonusClaimed)
    }

    @Test
    fun `KST 자정 경계 — 어제(KST) 로그만 있으면 오늘은 미체크 + streak 유지 (today=false)`() {
        attendanceRepo.save(AttendanceLog(userId = "user-1", checkInDate = yesterday, streak = 2, rewardBasicCoins = 20, bonus = false))
        val state = service.getState("user-1")
        assertFalse(state.today)
        assertEquals(2, state.streak)
        assertEquals(3, state.cycleDay)
        assertEquals(today, state.serverDateKst)
    }

    @Test
    fun `checkIn — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        assertEquals(ErrorCode.USER_NOT_FOUND, assertThrows<BusinessException> { service.checkIn("ghost") }.errorCode)
    }

    @Test
    fun `AttendanceProperties — 보드 코인은 정확히 7개여야 한다`() {
        assertThrows<IllegalArgumentException> { AttendanceProperties(board = AttendanceProperties.Board(coins = listOf(1, 2, 3))) }
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

        override fun findAllByUserIdAndCheckInDateBetween(
            userId: String,
            start: LocalDate,
            end: LocalDate,
        ): List<AttendanceLog> = store.values.filter { it.userId == userId && !it.checkInDate.isBefore(start) && !it.checkInDate.isAfter(end) }
    }
}
