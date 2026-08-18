package com.terraworld.api.record

import com.terraworld.api.notification.HabitCheerEvent
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.HabitCheer
import com.terraworld.domain.record.HabitCheerRepository
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.time.LocalDateTime
import java.util.Optional
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals

/**
 * HabitCheerService 커버 (apjek social loop 습관 응원).
 * - 성공: 원장 저장 + HabitCheerEvent 발행 (푸시/알림함은 listener 별도 경로)
 * - 검증: 메시지 길이 / 미존재 트래커 / 비연동 트래커 / 비참여자
 * - rate-limit: 일 3회/트래커 (KST 날짜), 다른 트래커는 독립 카운트
 */
class HabitCheerServiceTest {
    private lateinit var trackerRepository: HabitTrackerRepository
    private lateinit var cheerRepository: FakeHabitCheerRepository
    private lateinit var inviteRepository: InviteRepository
    private lateinit var userRepository: UserRepository
    private lateinit var eventPublisher: ApplicationEventPublisher
    private lateinit var service: HabitCheerService

    @BeforeEach
    fun setup() {
        trackerRepository = mock(HabitTrackerRepository::class.java)
        cheerRepository = FakeHabitCheerRepository()
        inviteRepository = mock(InviteRepository::class.java)
        userRepository = mock(UserRepository::class.java)
        eventPublisher = mock(ApplicationEventPublisher::class.java)
        service = HabitCheerService(trackerRepository, cheerRepository, inviteRepository, userRepository, eventPublisher)
    }

    /** 친구 연동 트래커 + 수락 invite(u↔friend) 기본 셋업. */
    private fun linkedTracker(
        trackerId: Long = 1L,
        ownerId: String = "u",
        linkId: Long = 100L,
    ) {
        whenever(trackerRepository.findById(trackerId)).thenReturn(
            Optional.of(
                HabitTracker(id = trackerId, userId = ownerId, title = "스트레칭", startDate = KstTime.today(), friendLinkId = linkId),
            ),
        )
        whenever(inviteRepository.findById(linkId)).thenReturn(
            Optional.of(
                Invite(
                    id = linkId,
                    code = "CODE$linkId",
                    inviterUserId = "u",
                    inviteeUserId = "friend",
                    expiresAt = LocalDateTime.now().plusDays(1),
                ),
            ),
        )
        whenever(userRepository.findById("u")).thenReturn(Optional.of(User(id = "u", nickname = "태겸")))
    }

    @Test
    fun `cheer 성공 — 원장 저장 + 상대에게 HabitCheerEvent 발행`() {
        linkedTracker()
        val saved = service.cheer("u", 1L, "오늘도 화이팅!")

        assertEquals("u", saved.fromUserId)
        assertEquals("friend", saved.toUserId)
        assertEquals(KstTime.today(), saved.cheeredDate)
        verify(eventPublisher).publishEvent(
            HabitCheerEvent(fromUserId = "u", toUserId = "friend", fromNickname = "태겸", message = "오늘도 화이팅!"),
        )
    }

    @Test
    fun `cheer — invitee 발신이면 상대는 inviter (방향 무관 해석)`() {
        linkedTracker()
        whenever(userRepository.findById("friend")).thenReturn(Optional.of(User(id = "friend", nickname = "재석")))
        val saved = service.cheer("friend", 1L, "너도 화이팅!")
        assertEquals("u", saved.toUserId)
    }

    @Test
    fun `cheer — 빈 메시지·100자 초과 INVALID_INPUT`() {
        linkedTracker()
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.cheer("u", 1L, "   ") }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.cheer("u", 1L, "가".repeat(101)) }.errorCode,
        )
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `cheer — 미존재 트래커 HABIT_NOT_FOUND`() {
        whenever(trackerRepository.findById(999L)).thenReturn(Optional.empty())
        assertEquals(
            ErrorCode.HABIT_NOT_FOUND,
            assertThrows<BusinessException> { service.cheer("u", 999L, "화이팅") }.errorCode,
        )
    }

    @Test
    fun `cheer — 친구 연동 아닌 트래커 INVALID_INPUT`() {
        whenever(trackerRepository.findById(1L)).thenReturn(
            Optional.of(HabitTracker(id = 1L, userId = "u", title = "혼자 습관", startDate = KstTime.today(), friendLinkId = null)),
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.cheer("u", 1L, "화이팅") }.errorCode,
        )
    }

    @Test
    fun `cheer — 링크 비참여자는 FORBIDDEN (무관 사용자 응원 차단)`() {
        linkedTracker()
        assertEquals(
            ErrorCode.FORBIDDEN,
            assertThrows<BusinessException> { service.cheer("stranger", 1L, "화이팅") }.errorCode,
        )
        assertEquals(0, cheerRepository.all().size)
    }

    @Test
    fun `cheer — 일 3회 초과 시 DAILY_LIMIT_EXCEEDED (4번째 거부)`() {
        linkedTracker()
        repeat(3) { service.cheer("u", 1L, "화이팅 $it") }
        assertEquals(
            ErrorCode.DAILY_LIMIT_EXCEEDED,
            assertThrows<BusinessException> { service.cheer("u", 1L, "4번째") }.errorCode,
        )
        assertEquals(3, cheerRepository.all().size)
    }

    @Test
    fun `cheer — 트래커별 독립 카운트 (다른 트래커는 한도 미공유)`() {
        linkedTracker(trackerId = 1L)
        linkedTracker(trackerId = 2L)
        repeat(3) { service.cheer("u", 1L, "화이팅 $it") }
        // 트래커 1 은 소진됐지만 트래커 2 는 발송 가능
        service.cheer("u", 2L, "다른 습관도 화이팅")
        assertEquals(4, cheerRepository.all().size)
    }

    @Test
    fun `cheer — 지난 날짜 기록은 오늘 한도에 미포함`() {
        linkedTracker()
        repeat(3) {
            cheerRepository.save(
                HabitCheer(
                    trackerId = 1L,
                    fromUserId = "u",
                    toUserId = "friend",
                    message = "어제 응원",
                    cheeredDate = KstTime.today().minusDays(1),
                ),
            )
        }
        val saved = service.cheer("u", 1L, "오늘 첫 응원")
        assertEquals(KstTime.today(), saved.cheeredDate)
    }

    @Test
    fun `cheer — 닉네임 조회 실패 시 '친구' 폴백`() {
        linkedTracker()
        whenever(userRepository.findById("u")).thenReturn(Optional.empty())
        service.cheer("u", 1L, "화이팅")
        verify(eventPublisher).publishEvent(any<HabitCheerEvent>())
    }

    private class FakeHabitCheerRepository :
        FakeJpaRepository<HabitCheer, Long>(),
        HabitCheerRepository {
        private val seq = AtomicLong(0)

        override fun extractId(entity: HabitCheer): Long = entity.id

        // IDENTITY 채번 재현 — id=0(미할당) 이면 reflection 으로 순번 할당.
        override fun assignId(entity: HabitCheer): HabitCheer {
            if (entity.id == 0L) {
                val field = HabitCheer::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.set(entity, seq.incrementAndGet())
            }
            return entity
        }

        override fun countByFromUserIdAndTrackerIdAndCheeredDate(
            fromUserId: String,
            trackerId: Long,
            cheeredDate: java.time.LocalDate,
        ): Long =
            store.values
                .count { it.fromUserId == fromUserId && it.trackerId == trackerId && it.cheeredDate == cheeredDate }
                .toLong()

        override fun acquireCheerLock(key: String): Int = 1
    }
}
