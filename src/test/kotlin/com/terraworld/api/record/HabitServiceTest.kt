package com.terraworld.api.record

import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.api.notification.HabitPairEvent
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.HabitCycle
import com.terraworld.domain.record.HabitCycleRepository
import com.terraworld.domain.record.HabitPairRequest
import com.terraworld.domain.record.HabitPairRequestKind
import com.terraworld.domain.record.HabitPairRequestRepository
import com.terraworld.domain.record.HabitPairRequestStatus
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.HabitTrackerResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HabitService 아프젝 v2 상태머신 커버 (§R2).
 * - 활성 1개 제한 / solo 생성 ACTIVE / friend 생성 PENDING + 미러 PENDING + 요청 + 알림
 * - 수락(양측 ACTIVE) / 거절(양측 BROKEN) / 취소(DELETE PENDING) / 만료(읽기 시 BROKEN)
 * - 체크인: PENDING 409 HABIT_NOT_ACTIVE, 7일째 COMPLETED_UNCLAIMED(보상 없음)
 * - complete: CAS + 원장 키 1회 지급, 재호출 alreadyClaimed / 동시 CAS 패자 멱등 재생
 * - extend: solo 즉시 ACTIVE 새 사이클 / friend EXTEND 요청 PENDING / 상대 중단 시 solo 연장
 * - 중단: 본인만 BROKEN, 상대 PARTNER_STOPPED + 보상 solo 100
 */
class HabitServiceTest {
    private lateinit var repo: FakeHabitTrackerRepository
    private lateinit var cycleRepo: FakeHabitCycleRepository
    private lateinit var requestRepo: FakeHabitPairRequestRepository
    private lateinit var grantService: GrantService
    private lateinit var inviteRepository: InviteRepository
    private lateinit var userRepository: UserRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: HabitService
    private val today: LocalDate = KstTime.today()

    @BeforeEach
    fun setup() {
        repo = FakeHabitTrackerRepository()
        cycleRepo = FakeHabitCycleRepository()
        requestRepo = FakeHabitPairRequestRepository()
        grantService = mock(GrantService::class.java)
        inviteRepository = mock(InviteRepository::class.java)
        userRepository = mock(UserRepository::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        whenever(grantService.grant(any(), any(), any(), any(), any(), any())).thenReturn(true)
        whenever(inviteRepository.findAcceptedBetween("u", "friend")).thenReturn(Optional.of(invite(100L)))
        whenever(userRepository.findById("u")).thenReturn(Optional.of(User(id = "u", nickname = "나")))
        whenever(userRepository.findById("friend")).thenReturn(Optional.of(User(id = "friend", nickname = "재석")))
        whenever(userRepository.findAllById(any<Iterable<String>>())).thenReturn(listOf(User(id = "friend", nickname = "재석"), User(id = "u", nickname = "나")))
        service = HabitService(repo, cycleRepo, requestRepo, grantService, inviteRepository, userRepository, eventPublisher, HabitProperties())
    }

    private fun tracker(
        id: Long,
        userId: String = "u",
        streak: Int = 0,
        lastChecked: LocalDate? = null,
        status: HabitStatus = HabitStatus.ACTIVE,
        partnerTrackerId: Long? = null,
        friendLinkId: Long? = null,
    ): HabitTracker {
        val t =
            HabitTracker(
                id = id,
                userId = userId,
                title = "스트레칭",
                startDate = today,
                currentStreakDays = streak,
                lastCheckedDate = lastChecked,
                status = status,
                partnerTrackerId = partnerTrackerId,
                friendLinkId = friendLinkId,
            )
        repo.save(t)
        val cycle = cycleRepo.save(HabitCycle(trackerId = id, userId = userId, cycleNo = 1, startedOn = today))
        t.currentCycleId = cycle.id
        return t
    }

    /** 서로 연결된 ACTIVE 페어 (요청 ACCEPTED). */
    private fun pair(): Pair<HabitTracker, HabitTracker> {
        val mine = tracker(1L, userId = "u", partnerTrackerId = 2L, friendLinkId = 100L)
        val partner = tracker(2L, userId = "friend", partnerTrackerId = 1L, friendLinkId = 100L)
        requestRepo.save(
            HabitPairRequest(
                requesterTrackerId = 1L,
                requesterUserId = "u",
                partnerTrackerId = 2L,
                partnerUserId = "friend",
                kind = HabitPairRequestKind.START,
                status = HabitPairRequestStatus.ACCEPTED,
                expiresAt = LocalDateTime.now().plusDays(7),
            ),
        )
        return mine to partner
    }

    // ─── 생성 / 제한 ───

    @Test
    fun `solo 생성 — 즉시 ACTIVE + 사이클 1 개시`() {
        val t = service.createTracker("u", "산책")
        assertEquals(HabitStatus.ACTIVE, t.status)
        assertTrue(t.currentCycleId != null)
        assertEquals(1, cycleRepo.all().single().cycleNo)
    }

    @Test
    fun `활성 습관이 있으면 HABIT_LIMIT_EXCEEDED (PENDING·COMPLETED_UNCLAIMED 포함)`() {
        tracker(1L, status = HabitStatus.COMPLETED_UNCLAIMED)
        val ex = assertThrows<BusinessException> { service.createTracker("u", "독서") }
        assertEquals(ErrorCode.HABIT_LIMIT_EXCEEDED, ex.errorCode)
    }

    @Test
    fun `종료(BROKEN·COMPLETED)된 습관만 있으면 새로 만들 수 있다`() {
        tracker(1L, status = HabitStatus.BROKEN)
        tracker(2L, status = HabitStatus.COMPLETED)
        assertEquals(HabitStatus.ACTIVE, service.createTracker("u", "독서").status)
    }

    @Test
    fun `friend 생성 — 내 트래커 PENDING + 상대 미러 PENDING + START 요청 + HABIT 알림`() {
        val mine = service.createTracker("u", "산책", "friend")
        assertEquals(HabitStatus.PENDING, mine.status)
        val mirror = repo.all().single { it.userId == "friend" }
        assertEquals(HabitStatus.PENDING, mirror.status)
        assertEquals("산책", mirror.title)
        assertEquals(mirror.id, mine.partnerTrackerId)
        assertEquals(mine.id, mirror.partnerTrackerId)
        val req = requestRepo.all().single()
        assertEquals(HabitPairRequestKind.START, req.kind)
        assertTrue(req.isOpen())
        val captor = argumentCaptor<Any>()
        verify(eventPublisher).publishEvent(captor.capture())
        val ev = captor.firstValue as HabitPairEvent
        assertEquals("friend", ev.toUserId)
        assertEquals("/record", ev.route)

        val view = service.resolveView("u", listOf(mine))[mine.id]!!
        assertEquals(HabitTrackerResponse.PartnerStatus.PENDING_SENT, view.partnerStatus)
        assertEquals(200L, view.rewardSparkle)
        assertEquals(HabitTrackerResponse.PartnerStatus.PENDING_RECEIVED, service.resolveView("friend", listOf(mirror))[mirror.id]!!.partnerStatus)
    }

    @Test
    fun `friend 생성 — 상대가 이미 활성 습관이면 HABIT_LIMIT_EXCEEDED`() {
        tracker(9L, userId = "friend")
        val ex = assertThrows<BusinessException> { service.createTracker("u", "산책", "friend") }
        assertEquals(ErrorCode.HABIT_LIMIT_EXCEEDED, ex.errorCode)
        assertTrue(repo.all().none { it.userId == "u" })
    }

    @Test
    fun `friend 생성 — 수락된 친구가 아니면 INVALID_INPUT`() {
        val ex = assertThrows<BusinessException> { service.createTracker("u", "산책", "stranger") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    // ─── 수락 / 거절 / 취소 / 만료 ───

    @Test
    fun `accept — 수신자 미러 id 로 수락하면 양측 ACTIVE + 사이클 개시 + partnerStatus ACCEPTED`() {
        val mine = service.createTracker("u", "산책", "friend")
        val mirror = repo.all().single { it.userId == "friend" }
        val accepted = service.accept("friend", mirror.id)
        assertEquals(HabitStatus.ACTIVE, accepted.status)
        assertEquals(HabitStatus.ACTIVE, repo.findById(mine.id).get().status)
        assertEquals(HabitPairRequestStatus.ACCEPTED, requestRepo.all().single().status)
        assertTrue(accepted.currentCycleId != null)
        assertEquals(HabitTrackerResponse.PartnerStatus.ACCEPTED, service.resolveView("u", listOf(mine))[mine.id]!!.partnerStatus)
    }

    @Test
    fun `accept — 요청자 자신의 트래커로 호출하면 HABIT_INVALID_STATE`() {
        val mine = service.createTracker("u", "산책", "friend")
        assertEquals(ErrorCode.HABIT_INVALID_STATE, assertThrows<BusinessException> { service.accept("u", mine.id) }.errorCode)
    }

    @Test
    fun `decline — START 거절은 양측 BROKEN + partnerStatus DECLINED`() {
        val mine = service.createTracker("u", "산책", "friend")
        val mirror = repo.all().single { it.userId == "friend" }
        service.decline("friend", mirror.id)
        assertEquals(HabitStatus.BROKEN, repo.findById(mine.id).get().status)
        assertEquals(HabitStatus.BROKEN, repo.findById(mirror.id).get().status)
        assertEquals(HabitTrackerResponse.PartnerStatus.DECLINED, service.resolveView("u", listOf(mine))[mine.id]!!.partnerStatus)
        assertTrue(service.listOpen("u").isEmpty())
    }

    @Test
    fun `DELETE on PENDING 요청자 — 취소, 양측 BROKEN + 요청 CANCELLED`() {
        val mine = service.createTracker("u", "산책", "friend")
        val mirror = repo.all().single { it.userId == "friend" }
        service.stop("u", mine.id)
        assertEquals(HabitPairRequestStatus.CANCELLED, requestRepo.all().single().status)
        assertEquals(HabitStatus.BROKEN, repo.findById(mirror.id).get().status)
        assertEquals(HabitTrackerResponse.PartnerStatus.CANCELLED, service.resolveView("u", listOf(mine))[mine.id]!!.partnerStatus)
    }

    @Test
    fun `만료된 요청은 목록 조회 시 EXPIRED + 양측 BROKEN`() {
        val mine = service.createTracker("u", "산책", "friend")
        val req = requestRepo.all().single()
        requestRepo.save(
            HabitPairRequest(
                id = req.id,
                requesterTrackerId = req.requesterTrackerId,
                requesterUserId = req.requesterUserId,
                partnerTrackerId = req.partnerTrackerId,
                partnerUserId = req.partnerUserId,
                kind = req.kind,
                expiresAt = LocalDateTime.now().minusMinutes(1),
            ),
        )
        assertTrue(service.listOpen("u").isEmpty())
        assertEquals(HabitStatus.BROKEN, repo.findById(mine.id).get().status)
        assertEquals(HabitPairRequestStatus.EXPIRED, requestRepo.all().single().status)
    }

    // ─── 체크인 ───

    @Test
    fun `checkIn — PENDING 트래커는 HABIT_NOT_ACTIVE`() {
        val mine = service.createTracker("u", "산책", "friend")
        assertEquals(ErrorCode.HABIT_NOT_ACTIVE, assertThrows<BusinessException> { service.checkIn("u", mine.id) }.errorCode)
    }

    @Test
    fun `checkIn 연속 — 어제 체크인 후 오늘 streak++`() {
        tracker(1L, streak = 2, lastChecked = today.minusDays(1))
        val r = service.checkIn("u", 1L)
        assertEquals(3, r.tracker.currentStreakDays)
        assertFalse(r.cycleCompleted)
    }

    @Test
    fun `checkIn 공백 — 3일 전 체크인이면 streak 1 재시작`() {
        tracker(1L, streak = 5, lastChecked = today.minusDays(3))
        assertEquals(1, service.checkIn("u", 1L).tracker.currentStreakDays)
    }

    @Test
    fun `checkIn 같은날 재체크인 — 멱등 no-op`() {
        tracker(1L, streak = 3, lastChecked = today)
        assertEquals(3, service.checkIn("u", 1L).tracker.currentStreakDays)
    }

    @Test
    fun `checkIn 7일째 — COMPLETED_UNCLAIMED + cycleCompleted, 보상 지급 없음`() {
        tracker(1L, streak = 6, lastChecked = today.minusDays(1))
        val r = service.checkIn("u", 1L)
        assertTrue(r.cycleCompleted)
        assertEquals(HabitStatus.COMPLETED_UNCLAIMED, r.tracker.status)
        assertEquals(7, r.tracker.currentStreakDays)
        assertTrue(r.tracker.cycleCompletedAt != null)
        verify(grantService, never()).grant(any(), any(), any(), any(), any(), any())
        // 완주 대기 상태에서는 재체크인 불가
        assertEquals(ErrorCode.HABIT_NOT_ACTIVE, assertThrows<BusinessException> { service.checkIn("u", 1L) }.errorCode)
    }

    @Test
    fun `checkIn 미존재 트래커 — HABIT_NOT_FOUND`() {
        assertEquals(ErrorCode.HABIT_NOT_FOUND, assertThrows<BusinessException> { service.checkIn("u", 999L) }.errorCode)
    }

    // ─── 보상 수령(complete) ───

    @Test
    fun `complete — COMPLETED 전이 + 원장 키로 SPARKLE 100 1회 지급, 재호출은 alreadyClaimed`() {
        val t = tracker(1L, streak = 7, status = HabitStatus.COMPLETED_UNCLAIMED)
        val r = service.complete("u", 1L)
        assertEquals(HabitStatus.COMPLETED, r.tracker.status)
        assertEquals(100L, r.sparkleGranted)
        assertFalse(r.alreadyClaimed)
        assertEquals(1, r.tracker.completedCycles)
        verify(grantService).grant(eq("u"), eq(GrantType.CURRENCY), eq("SPARKLE"), eq(100L), eq("habit:${t.currentCycleId}:reward:u"), any())
        assertTrue(cycleRepo.findById(t.currentCycleId!!).get().rewardClaimedAt != null)

        val replay = service.complete("u", 1L)
        assertTrue(replay.alreadyClaimed)
        assertEquals(0L, replay.sparkleGranted)
        verify(grantService, times(1)).grant(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `complete — 원장이 이미 지급됐으면(동시 요청 패자) alreadyClaimed true sparkle 0`() {
        tracker(1L, streak = 7, status = HabitStatus.COMPLETED_UNCLAIMED)
        whenever(grantService.grant(any(), any(), any(), any(), any(), any())).thenReturn(false)
        val r = service.complete("u", 1L)
        assertTrue(r.alreadyClaimed)
        assertEquals(0L, r.sparkleGranted)
    }

    @Test
    fun `complete — ACTIVE 트래커는 HABIT_INVALID_STATE`() {
        tracker(1L)
        assertEquals(ErrorCode.HABIT_INVALID_STATE, assertThrows<BusinessException> { service.complete("u", 1L) }.errorCode)
    }

    @Test
    fun `complete — 페어 양측 참여 중이면 200`() {
        val (mine, _) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        val r = service.complete("u", 1L)
        assertEquals(200L, r.sparkleGranted)
    }

    @Test
    fun `complete — 상대가 중단(BROKEN)했으면 solo 100`() {
        val (mine, partner) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        partner.status = HabitStatus.BROKEN
        assertEquals(100L, service.complete("u", 1L).sparkleGranted)
    }

    // ─── 연장(extend) ───

    @Test
    fun `extend solo — 보상 지급 + 새 사이클 ACTIVE (completedCycles+1, streak 0)`() {
        val t = tracker(1L, streak = 7, lastChecked = today, status = HabitStatus.COMPLETED_UNCLAIMED)
        val firstCycle = t.currentCycleId
        val r = service.extend("u", 1L)
        assertEquals(HabitStatus.ACTIVE, r.tracker.status)
        assertEquals(100L, r.sparkleGranted)
        assertEquals(1, r.tracker.completedCycles)
        assertEquals(0, r.tracker.currentStreakDays)
        assertNull(r.tracker.cycleCompletedAt)
        assertTrue(r.tracker.currentCycleId != firstCycle)
        assertEquals(2, cycleRepo.findById(r.tracker.currentCycleId!!).get().cycleNo)
        // 새 사이클에서는 extend 재호출 불가 (스펙: COMPLETED_UNCLAIMED 아님 → 409)
        assertEquals(ErrorCode.HABIT_INVALID_STATE, assertThrows<BusinessException> { service.extend("u", 1L) }.errorCode)
    }

    @Test
    fun `extend friend — 보상 지급 + EXTEND 요청 + 내 트래커 PENDING, 상대 수락 시 양측 ACTIVE`() {
        val (mine, partner) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        partner.status = HabitStatus.COMPLETED_UNCLAIMED
        val r = service.extend("u", 1L)
        assertEquals(HabitStatus.PENDING, r.tracker.status)
        assertEquals(200L, r.sparkleGranted)
        val req = requestRepo.all().single { it.kind == HabitPairRequestKind.EXTEND }
        assertTrue(req.isOpen())
        assertEquals(HabitTrackerResponse.ExtendStatus.PENDING_SENT, service.resolveView("u", listOf(mine))[mine.id]!!.extendStatus)
        assertEquals(HabitTrackerResponse.ExtendStatus.PENDING_RECEIVED, service.resolveView("friend", listOf(partner))[partner.id]!!.extendStatus)
        // 수락 전 체크인 불가
        assertEquals(ErrorCode.HABIT_NOT_ACTIVE, assertThrows<BusinessException> { service.checkIn("u", 1L) }.errorCode)

        val accepted = service.accept("friend", 2L)
        assertEquals(HabitStatus.ACTIVE, accepted.status)
        assertEquals(HabitStatus.ACTIVE, repo.findById(1L).get().status)
        assertEquals(1, accepted.completedCycles)
        // 수신자도 자기 보상 수령 (pair 200)
        verify(grantService).grant(eq("friend"), eq(GrantType.CURRENCY), eq("SPARKLE"), eq(200L), any(), any())
        assertEquals(HabitTrackerResponse.ExtendStatus.ACCEPTED, service.resolveView("u", listOf(mine))[mine.id]!!.extendStatus)
    }

    @Test
    fun `extend friend — 상대가 이미 중단했으면 solo 연장 (연동 해제, 보상 100)`() {
        val (mine, partner) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        partner.status = HabitStatus.BROKEN
        val r = service.extend("u", 1L)
        assertEquals(HabitStatus.ACTIVE, r.tracker.status)
        assertEquals(100L, r.sparkleGranted)
        assertNull(r.tracker.partnerTrackerId)
        assertTrue(requestRepo.all().none { it.kind == HabitPairRequestKind.EXTEND })
    }

    @Test
    fun `extend — 상대 연장 요청이 열려 있으면 내 extend 는 수락으로 처리된다`() {
        val (mine, partner) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        partner.status = HabitStatus.COMPLETED_UNCLAIMED
        service.extend("friend", 2L) // 상대가 먼저 연장
        val r = service.extend("u", 1L)
        assertEquals(HabitStatus.ACTIVE, r.tracker.status)
        assertEquals(HabitStatus.ACTIVE, repo.findById(2L).get().status)
        assertEquals(HabitPairRequestStatus.ACCEPTED, requestRepo.all().single { it.kind == HabitPairRequestKind.EXTEND }.status)
    }

    @Test
    fun `extend EXTEND 거절 — 요청자만 BROKEN, 수신자는 유지`() {
        val (mine, partner) = pair()
        mine.status = HabitStatus.COMPLETED_UNCLAIMED
        partner.status = HabitStatus.COMPLETED_UNCLAIMED
        service.extend("u", 1L)
        service.decline("friend", 2L)
        assertEquals(HabitStatus.BROKEN, repo.findById(1L).get().status)
        assertEquals(HabitStatus.COMPLETED_UNCLAIMED, repo.findById(2L).get().status)
    }

    // ─── 중단 ───

    @Test
    fun `stop ACTIVE — 본인만 BROKEN, 상대는 PARTNER_STOPPED + 보상 solo 100 + 알림`() {
        val (mine, partner) = pair()
        service.stop("u", 1L)
        assertEquals(HabitStatus.BROKEN, mine.status)
        assertEquals(HabitStatus.ACTIVE, partner.status)
        val view = service.resolveView("friend", listOf(partner))[partner.id]!!
        assertEquals(HabitTrackerResponse.PartnerStatus.PARTNER_STOPPED, view.partnerStatus)
        assertEquals(100L, view.rewardSparkle)
        val captor = argumentCaptor<Any>()
        verify(eventPublisher).publishEvent(captor.capture())
        assertEquals("friend", (captor.firstValue as HabitPairEvent).toUserId)
    }

    @Test
    fun `stop 은 멱등 — 이미 종료된 트래커는 상태 유지 no-op`() {
        val t = tracker(1L, status = HabitStatus.COMPLETED)
        service.stop("u", 1L)
        assertEquals(HabitStatus.COMPLETED, t.status)
    }

    @Test
    fun `stop 미존재 또는 타인 트래커 — HABIT_NOT_FOUND`() {
        tracker(1L, userId = "other")
        assertEquals(ErrorCode.HABIT_NOT_FOUND, assertThrows<BusinessException> { service.stop("u", 1L) }.errorCode)
        assertEquals(ErrorCode.HABIT_NOT_FOUND, assertThrows<BusinessException> { service.stop("u", 999L) }.errorCode)
    }

    @Test
    fun `stop 후 새 습관 생성 가능 (활성 1개 제한은 열린 상태만 검사)`() {
        val first = service.createTracker("u", "산책")
        service.stop("u", first.id)
        assertEquals(HabitStatus.ACTIVE, service.createTracker("u", "독서").status)
    }

    // ─── 응답 뷰(resolveView) ───

    @Test
    fun `resolveView — solo 는 NONE, 닉네임·상대 userId 해석`() {
        val (mine, _) = pair()
        val solo = tracker(3L, userId = "u2")
        val views = service.resolveView("u", listOf(mine, solo))
        assertEquals("friend", views[1L]?.friendUserId)
        assertEquals("재석", views[1L]?.friendNickname)
        assertEquals(HabitTrackerResponse.PartnerStatus.ACCEPTED, views[1L]?.partnerStatus)
        assertEquals(HabitTrackerResponse.PartnerStatus.NONE, views[3L]?.partnerStatus)
        assertEquals(100L, views[3L]?.rewardSparkle)
    }

    private fun invite(id: Long) = Invite(id = id, code = "CODE$id", inviterUserId = "u", inviteeUserId = "friend", expiresAt = LocalDateTime.now().plusDays(1))

    // ─── 페이크 ───

    private class FakeHabitTrackerRepository :
        FakeJpaRepository<HabitTracker, Long>(),
        HabitTrackerRepository {
        private var seq = 1000L

        override fun extractId(entity: HabitTracker): Long = entity.id

        override fun assignId(entity: HabitTracker): HabitTracker {
            if (entity.id != 0L) return entity
            val idField = HabitTracker::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, seq++)
            return entity
        }

        override fun findAllByUserIdAndStatusIn(
            userId: String,
            statuses: Collection<HabitStatus>,
        ): List<HabitTracker> = store.values.filter { it.userId == userId && it.status in statuses }

        override fun countByUserIdAndStatusIn(
            userId: String,
            statuses: Collection<HabitStatus>,
        ): Long = findAllByUserIdAndStatusIn(userId, statuses).size.toLong()

        override fun findByIdAndUserId(
            id: Long,
            userId: String,
        ): HabitTracker? = store.values.firstOrNull { it.id == id && it.userId == userId }

        override fun acquireHabitPairLock(key: String): Int = 1

        override fun casStatus(
            id: Long,
            userId: String,
            from: HabitStatus,
            to: HabitStatus,
        ): Int {
            val t = store.values.firstOrNull { it.id == id && it.userId == userId && it.status == from } ?: return 0
            t.status = to
            t.version += 1
            return 1
        }

        override fun markBroken(
            id: Long,
            userId: String,
        ): Int {
            val t = store.values.firstOrNull { it.id == id && it.userId == userId && it.status in HabitStatus.OPEN } ?: return 0
            t.status = HabitStatus.BROKEN
            t.version += 1
            return 1
        }
    }

    private class FakeHabitCycleRepository :
        FakeJpaRepository<HabitCycle, Long>(),
        HabitCycleRepository {
        private var seq = 1L

        override fun extractId(entity: HabitCycle): Long = entity.id

        override fun assignId(entity: HabitCycle): HabitCycle {
            if (entity.id != 0L) return entity
            val idField = HabitCycle::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, seq++)
            return entity
        }
    }

    private class FakeHabitPairRequestRepository :
        FakeJpaRepository<HabitPairRequest, Long>(),
        HabitPairRequestRepository {
        private var seq = 1L

        override fun extractId(entity: HabitPairRequest): Long = entity.id

        override fun assignId(entity: HabitPairRequest): HabitPairRequest {
            if (entity.id != 0L) return entity
            val idField = HabitPairRequest::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, seq++)
            return entity
        }

        override fun findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(
            requesterTrackerIds: Collection<Long>,
            partnerTrackerIds: Collection<Long>,
        ): List<HabitPairRequest> = store.values.filter { it.requesterTrackerId in requesterTrackerIds || it.partnerTrackerId in partnerTrackerIds }
    }
}
