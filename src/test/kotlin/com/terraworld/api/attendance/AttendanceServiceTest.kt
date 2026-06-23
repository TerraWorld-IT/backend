package com.terraworld.api.attendance

import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.attendance.AttendanceLog
import com.terraworld.domain.attendance.AttendanceLogRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * AttendanceService 의 핵심 분기 커버.
 *  - checkIn 해피 패스: streak 증가 + 기본 보상(5) 지급 + basicCoin 누적
 *  - 7일 보너스 자격: streak 6(어제) → 오늘 체크인 시 streak 7 → 보너스 보상(20) + bonus=true
 *  - 같은 날 중복 체크인 분기: 오늘 로그 존재 시 ATTENDANCE_ALREADY_CHECKED
 *  - 사용자 미존재: USER_NOT_FOUND
 *  - getState: streak 단절(어제 로그 없음) 시 nextStreak=1, today=false
 *
 * KstTime.today() 는 실제 시스템 일자를 쓰므로 latest 로그의 checkInDate 를
 * today()/today().minusDays(1) 로 상대 계산해 streak 분기를 유도한다.
 */
class AttendanceServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var attendanceRepo: FakeAttendanceLogRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var service: AttendanceService

    private lateinit var user: User

    private val today: LocalDate = KstTime.today()
    private val yesterday: LocalDate = today.minusDays(1)

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        attendanceRepo = FakeAttendanceLogRepository()
        tokenRepo = FakeUserTokenRepository()

        val walletBuilder = WalletBuilder(tokenRepo)
        val auditService = org.mockito.Mockito.mock(AuditService::class.java)
        // N2 (구현 계획서 v4): wallet ledger append 경로는 본 test 범위 밖 — mock.
        val walletTransactionService =
            org.mockito.Mockito.mock(com.terraworld.api.wallet.WalletTransactionService::class.java)

        service =
            AttendanceService(
                userRepo,
                attendanceRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
            )

        user = User(id = "user-1", nickname = "테스터", basicCoin = 0)
        userRepo.save(user)
    }

    // ─── checkIn 해피 패스 ───────────────────────────────────────

    @Test
    fun `checkIn 해피 패스 — 첫 출석은 streak 1 + 기본 보상 5`() {
        val response = service.checkIn("user-1")

        assertEquals(1, response.attendance.streak)
        assertEquals(5, response.reward.basicCoins)
        assertFalse(response.reward.bonus)
        assertTrue(response.attendance.today)

        val u = userRepo.findById("user-1").get()
        assertEquals(5L, u.basicCoin)

        // 오늘 로그가 저장돼야 한다
        val log = attendanceRepo.findByUserIdAndCheckInDate("user-1", today)
        assertTrue(log.isPresent)
        assertEquals(1, log.get().streak)
    }

    @Test
    fun `checkIn — 어제 streak 3 이면 오늘 streak 4 로 증가`() {
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = yesterday,
                streak = 3,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

        val response = service.checkIn("user-1")

        assertEquals(4, response.attendance.streak)
        assertEquals(5, response.reward.basicCoins)
        assertFalse(response.reward.bonus)
    }

    // ─── 7일 보너스 자격 ─────────────────────────────────────────

    @Test
    fun `checkIn — 어제 streak 6 이면 오늘 streak 7 로 보너스 20 지급`() {
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = yesterday,
                streak = 6,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

        val response = service.checkIn("user-1")

        assertEquals(7, response.attendance.streak)
        assertEquals(20, response.reward.basicCoins)
        assertTrue(response.reward.bonus)

        val u = userRepo.findById("user-1").get()
        assertEquals(20L, u.basicCoin)
    }

    // ─── 같은 날 중복 체크인 분기 ───────────────────────────────

    @Test
    fun `checkIn — 오늘 이미 체크인했으면 ATTENDANCE_ALREADY_CHECKED`() {
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = today,
                streak = 1,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

        val ex =
            assertThrows<BusinessException> {
                service.checkIn("user-1")
            }
        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.errorCode)

        // 잔고 변화 없어야 함
        val u = userRepo.findById("user-1").get()
        assertEquals(0L, u.basicCoin)
    }

    @Test
    fun `checkIn — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.checkIn("ghost")
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    // ─── getState ───────────────────────────────────────────────

    @Test
    fun `getState — 어제 로그 없이 과거 로그만 있으면 streak 단절 nextStreak 1`() {
        // 그제(이틀 전) 로그만 존재 → 어제 단절 → computeNextStreak = 0
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = today.minusDays(2),
                streak = 5,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

        val state = service.getState("user-1")

        assertFalse(state.today)
        // currentStreak 은 단절돼 0, longestStreak 은 과거 최댓값 5
        assertEquals(0, state.streak)
        assertEquals(5, state.longestStreak)
        // nextStreak = 1 → 보너스 아님 → 기본 5
        assertEquals(5, state.rewardBasicCoins)
        assertEquals(false, state.bonusEligible)
    }

    @Test
    fun `getState — 오늘 이미 체크인했으면 today=true + nextStreak 미증가`() {
        // 오늘 로그(streak 3) 존재 → today=true, nextStreak = currentStreak(3) (증가 없음).
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = today,
                streak = 3,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

        val state = service.getState("user-1")

        assertTrue(state.today)
        assertEquals(3, state.streak)
        // nextStreak=3 → 7 배수 아님 → 보너스 아님
        assertEquals(false, state.bonusEligible)
        assertEquals(5, state.rewardBasicCoins)
    }

    @Test
    fun `getState — 어제 streak 6 이면 nextStreak 7 로 보너스 자격(20)`() {
        // 어제 로그(streak 6) + 오늘 미체크인 → nextStreak = 6+1 = 7 → 보너스 자격.
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = yesterday,
                streak = 6,
                rewardBasicCoins = 5,
                bonus = false,
            ),
        )

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
    }

    private class FakeUserTokenRepository :
        FakeJpaRepository<UserToken, Long>(),
        UserTokenRepository {
        override fun extractId(entity: UserToken): Long = entity.id

        override fun findByUserIdAndCategoryId(
            userId: String,
            categoryId: Long,
        ): Optional<UserToken> =
            Optional.ofNullable(
                store.values.firstOrNull { it.user.id == userId && it.category.id == categoryId },
            )

        override fun findAllByUserId(userId: String): List<UserToken> = store.values.filter { it.user.id == userId }
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
