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
    private lateinit var inviteRepository: InviteRepository
    private lateinit var userRepository: com.terraworld.domain.user.UserRepository
    private lateinit var service: HabitService
    private val today = KstTime.today()

    @BeforeEach
    fun setup() {
        repo = FakeHabitTrackerRepository()
        currencyService = mock(CurrencyService::class.java)
        inviteRepository = mock(InviteRepository::class.java)
        userRepository = mock(com.terraworld.domain.user.UserRepository::class.java)
        service =
            HabitService(
                repo,
                currencyService,
                inviteRepository,
                userRepository,
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
    fun `checkIn cycle 완료 + 상대 트래커 BROKEN — 2배 아님 (중단·재생성 파밍 차단, Codex R1 no_1)`() {
        tracker(1L, userId = "u", streak = 6, lastChecked = today.minusDays(1), friendLinkId = 100L)
        val partner = tracker(2L, userId = "friend", completedCycles = 1, friendLinkId = 100L)
        partner.status = HabitStatus.BROKEN
        val r = service.checkIn("u", 1L)
        assertEquals(120L, r.sparkleGranted)
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

    // ─── stop (2026-07-21 M1: 종료 수단 신설) ───

    @Test
    fun `stop — ACTIVE 트래커를 BROKEN 으로 전환`() {
        val t = tracker(1L)
        service.stop("u", 1L)
        assertEquals(HabitStatus.BROKEN, t.status)
    }

    @Test
    fun `stop 은 멱등 — 이미 종료된 트래커는 상태 유지 no-op`() {
        val t = tracker(1L)
        t.status = HabitStatus.COMPLETED
        service.stop("u", 1L)
        assertEquals(HabitStatus.COMPLETED, t.status)
    }

    @Test
    fun `stop 미존재 또는 타인 트래커 — HABIT_NOT_FOUND`() {
        tracker(1L, userId = "other")
        assertEquals(
            ErrorCode.HABIT_NOT_FOUND,
            assertThrows<BusinessException> { service.stop("u", 1L) }.errorCode,
        )
        assertEquals(
            ErrorCode.HABIT_NOT_FOUND,
            assertThrows<BusinessException> { service.stop("u", 999L) }.errorCode,
        )
    }

    @Test
    fun `stop 후 같은 친구와 새 공동 습관 생성 가능 (친구쌍 1개 제한은 ACTIVE 만 검사)`() {
        org.mockito.kotlin
            .whenever(inviteRepository.findAcceptedBetween("u", "friend"))
            .thenReturn(java.util.Optional.of(invite(100L)))
        val first = service.createTracker("u", "산책", "friend")
        val dup = assertThrows<BusinessException> { service.createTracker("u", "독서", "friend") }
        assertEquals(ErrorCode.INVALID_INPUT, dup.errorCode)
        service.stop("u", first.id)
        val second = service.createTracker("u", "독서", "friend")
        assertEquals(100L, second.friendLinkId)
    }

    // ─── resolveFriendMeta (2026-07-21 M2: 상대 참여 여부 노출) ───

    @Test
    fun `resolveFriendMeta — 상대 userId·닉네임·참여중(partnerActive true)`() {
        val mine = tracker(1L, userId = "u", friendLinkId = 100L)
        tracker(2L, userId = "friend", friendLinkId = 100L)
        val solo = tracker(3L, userId = "u")
        org.mockito.kotlin
            .whenever(inviteRepository.findAllById(listOf(100L)))
            .thenReturn(listOf(invite(100L)))
        org.mockito.kotlin
            .whenever(userRepository.findAllById(listOf("friend")))
            .thenReturn(
                listOf(
                    com.terraworld.domain.user
                        .User(id = "friend", nickname = "재석"),
                ),
            )
        val meta = service.resolveFriendMeta("u", listOf(mine, solo))
        assertEquals("friend", meta[1L]?.friendUserId)
        assertEquals("재석", meta[1L]?.friendNickname)
        assertTrue(meta[1L]!!.partnerActive)
        assertEquals(null, meta[3L])
    }

    @Test
    fun `resolveFriendMeta — 상대가 아직 안 만들었으면 partnerActive false`() {
        val mine = tracker(1L, userId = "u", friendLinkId = 100L)
        org.mockito.kotlin
            .whenever(inviteRepository.findAllById(listOf(100L)))
            .thenReturn(listOf(invite(100L)))
        org.mockito.kotlin
            .whenever(userRepository.findAllById(listOf("friend")))
            .thenReturn(
                listOf(
                    com.terraworld.domain.user
                        .User(id = "friend", nickname = "재석"),
                ),
            )
        val meta = service.resolveFriendMeta("u", listOf(mine))
        assertFalse(meta[1L]!!.partnerActive)
    }

    @Test
    fun `resolveFriendMeta — 상대 트래커가 BROKEN 이면 partnerActive false`() {
        val mine = tracker(1L, userId = "u", friendLinkId = 100L)
        val partner = tracker(2L, userId = "friend", friendLinkId = 100L)
        partner.status = HabitStatus.BROKEN
        org.mockito.kotlin
            .whenever(inviteRepository.findAllById(listOf(100L)))
            .thenReturn(listOf(invite(100L)))
        org.mockito.kotlin
            .whenever(userRepository.findAllById(listOf("friend")))
            .thenReturn(
                listOf(
                    com.terraworld.domain.user
                        .User(id = "friend", nickname = "재석"),
                ),
            )
        val meta = service.resolveFriendMeta("u", listOf(mine))
        assertFalse(meta[1L]!!.partnerActive)
    }

    private fun invite(id: Long) =
        com.terraworld.domain.social.Invite(
            id = id,
            code = "CODE$id",
            inviterUserId = "u",
            inviteeUserId = "friend",
            expiresAt =
                java.time.LocalDateTime
                    .now()
                    .plusDays(1),
        )

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

        override fun findAllByFriendLinkIdIn(friendLinkIds: Collection<Long>): List<HabitTracker> = store.values.filter { it.friendLinkId in friendLinkIds }

        override fun markBroken(
            id: Long,
            userId: String,
        ): Int {
            val t = store.values.firstOrNull { it.id == id && it.userId == userId && it.status == HabitStatus.ACTIVE } ?: return 0
            t.status = HabitStatus.BROKEN
            t.version += 1
            return 1
        }

        override fun acquireHabitPairLock(key: String): Int = 1
    }
}
