package com.terraworld.api.record

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.InviteRepository
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HabitService 커버 (낙서장 P1 습관/일상 분리).
 * - checkIn 연속 streak++ / 공백 재시작 / 같은날 멱등
 * - 7일 cycle 완료 → completedCycles++ + streak 리셋 + 반짝이 120 지급
 * - 친구 연동 + 양측 완료 → 240
 * - 종료 트래커 INVALID_INPUT / 미존재 HABIT_NOT_FOUND
 */
class HabitServiceTest {
    private lateinit var repo: FakeHabitTrackerRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var service: HabitService
    private val today = KstTime.today()

    @BeforeEach
    fun setup() {
        repo = FakeHabitTrackerRepository()
        currencyService = mock(CurrencyService::class.java)
        service =
            HabitService(
                repo,
                currencyService,
                mock(InviteRepository::class.java),
                mock(com.terraworld.domain.user.UserRepository::class.java),
                mock(org.springframework.context.ApplicationEventPublisher::class.java),
            )
    }

    private fun tracker(
        id: Long,
        userId: String = "u",
        streak: Int = 0,
        lastChecked: java.time.LocalDate? = null,
        completedCycles: Int = 0,
        friendLinkId: Long? = null,
    ) = HabitTracker(
        id = id,
        userId = userId,
        title = "스트레칭",
        startDate = today,
        currentStreakDays = streak,
        completedCycles = completedCycles,
        lastCheckedDate = lastChecked,
        friendLinkId = friendLinkId,
    ).also { repo.save(it) }

    @Test
    fun `checkIn 연속 — 어제 체크인 후 오늘 streak++`() {
        tracker(1L, streak = 2, lastChecked = today.minusDays(1))
        val r = service.checkIn("u", 1L)
        assertEquals(3, r.tracker.currentStreakDays)
        assertFalse(r.cycleCompleted)
        verify(currencyService, never()).credit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn 공백 — 3일 전 체크인이면 streak 1 재시작`() {
        tracker(1L, streak = 5, lastChecked = today.minusDays(3))
        val r = service.checkIn("u", 1L)
        assertEquals(1, r.tracker.currentStreakDays)
    }

    @Test
    fun `checkIn 같은날 재체크인 — 멱등 no-op`() {
        tracker(1L, streak = 3, lastChecked = today)
        val r = service.checkIn("u", 1L)
        assertEquals(3, r.tracker.currentStreakDays)
        assertEquals(0, r.sparkleGranted)
        verify(currencyService, never()).credit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn 7일 cycle 완료 — completedCycles++ streak 리셋 + 반짝이 120`() {
        tracker(1L, streak = 6, lastChecked = today.minusDays(1))
        val r = service.checkIn("u", 1L)
        assertTrue(r.cycleCompleted)
        assertEquals(1, r.tracker.completedCycles)
        assertEquals(0, r.tracker.currentStreakDays)
        assertEquals(120L, r.sparkleGranted)
        verify(currencyService).credit(eq("u"), eq("SPARKLE"), eq(120L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn cycle 완료 + 친구 양측 완료 — 반짝이 240`() {
        tracker(1L, userId = "u", streak = 6, lastChecked = today.minusDays(1), friendLinkId = 100L)
        // 친구 트래커: 같은 link, 이미 cycle 1 완료
        tracker(2L, userId = "friend", completedCycles = 1, friendLinkId = 100L)
        val r = service.checkIn("u", 1L)
        assertEquals(240L, r.sparkleGranted)
        verify(currencyService).credit(eq("u"), eq("SPARKLE"), eq(240L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `checkIn 종료된 트래커 — INVALID_INPUT`() {
        val t = tracker(1L)
        t.status = HabitStatus.COMPLETED
        val ex = assertThrows<BusinessException> { service.checkIn("u", 1L) }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `checkIn 미존재 트래커 — HABIT_NOT_FOUND`() {
        val ex = assertThrows<BusinessException> { service.checkIn("u", 999L) }
        assertEquals(ErrorCode.HABIT_NOT_FOUND, ex.errorCode)
    }

    private class FakeHabitTrackerRepository :
        FakeJpaRepository<HabitTracker, Long>(),
        HabitTrackerRepository {
        override fun extractId(entity: HabitTracker): Long = entity.id

        override fun findAllByUserIdAndStatus(
            userId: String,
            status: HabitStatus,
        ): List<HabitTracker> = store.values.filter { it.userId == userId && it.status == status }

        override fun findByIdAndUserId(
            id: Long,
            userId: String,
        ): HabitTracker? = store.values.firstOrNull { it.id == id && it.userId == userId }

        override fun findAllByFriendLinkId(friendLinkId: Long): List<HabitTracker> = store.values.filter { it.friendLinkId == friendLinkId }

        override fun acquireHabitPairLock(key: String): Int = 1
    }
}
