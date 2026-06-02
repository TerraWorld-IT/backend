package com.terraworld.api.attendance

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.api.wallet.WalletTransactionService
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
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import java.time.LocalDate
import java.util.Optional

/**
 * AttendanceService 출석 streak + 보너스 보상 로직.
 * BASE 5 / 7일 streak 마다 BONUS 20. 끊긴 streak 리셋 / 중복 체크인 차단 / getState eligible 검증.
 * (보상 금액 분기 = 실 화폐 로직 — 회귀 가드.)
 */
class AttendanceServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var attendanceRepo: FakeAttendanceLogRepository
    private lateinit var service: AttendanceService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        attendanceRepo = FakeAttendanceLogRepository()
        val tokenRepo = FakeUserTokenRepository()
        service =
            AttendanceService(
                userRepo,
                attendanceRepo,
                CurrencyBuilder(tokenRepo),
                mock(AuditService::class.java),
                mock(WalletTransactionService::class.java),
            )
        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    /** daysAgo 전에 streak 값으로 마지막 출석 기록을 심는다. */
    private fun seed(
        daysAgo: Long,
        streak: Int,
    ) {
        attendanceRepo.save(
            AttendanceLog(
                userId = "user-1",
                checkInDate = KstTime.today().minusDays(daysAgo),
                streak = streak,
                rewardBasicCoins = 5,
            ),
        )
    }

    @Test
    fun `첫 체크인 — streak 1 + BASE 5`() {
        val r = service.checkIn("user-1")
        assertEquals(1, r.attendance.streak)
        assertEquals(5, r.reward.basicCoins)
        assertEquals(false, r.reward.bonus)
        assertEquals(5, userRepo.findById("user-1").get().basicCoin)
    }

    @Test
    fun `어제 출석 후 오늘 — streak +1`() {
        seed(daysAgo = 1, streak = 3)
        val r = service.checkIn("user-1")
        assertEquals(4, r.attendance.streak)
        assertEquals(5, r.reward.basicCoins)
    }

    @Test
    fun `7일째 — 보너스 20`() {
        seed(daysAgo = 1, streak = 6)
        val r = service.checkIn("user-1")
        assertEquals(7, r.attendance.streak)
        assertEquals(true, r.reward.bonus)
        assertEquals(20, r.reward.basicCoins)
        assertEquals(20, userRepo.findById("user-1").get().basicCoin)
    }

    @Test
    fun `8일째 — 보너스 아님 5`() {
        seed(daysAgo = 1, streak = 7)
        val r = service.checkIn("user-1")
        assertEquals(8, r.attendance.streak)
        assertEquals(false, r.reward.bonus)
        assertEquals(5, r.reward.basicCoins)
    }

    @Test
    fun `streak 끊기면(2일 전 마지막) 1로 리셋`() {
        seed(daysAgo = 2, streak = 5)
        val r = service.checkIn("user-1")
        assertEquals(1, r.attendance.streak)
        assertEquals(5, r.reward.basicCoins)
    }

    @Test
    fun `오늘 이미 체크인 시 ATTENDANCE_ALREADY_CHECKED`() {
        seed(daysAgo = 0, streak = 1)
        val ex = assertThrows<BusinessException> { service.checkIn("user-1") }
        assertEquals(ErrorCode.ATTENDANCE_ALREADY_CHECKED, ex.errorCode)
    }

    @Test
    fun `getState — streak 6 + 오늘 미체크 시 다음 보너스 eligible(20)`() {
        seed(daysAgo = 1, streak = 6)
        val s = service.getState("user-1")
        assertEquals(false, s.today)
        assertEquals(6, s.streak)
        assertEquals(true, s.bonusEligible)
        assertEquals(20, s.rewardBasicCoins)
    }

    // ─── Fakes ──────────────────────────────────────────────
    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeAttendanceLogRepository :
        FakeJpaRepository<AttendanceLog, Long>(),
        AttendanceLogRepository {
        private var nextId = 1L

        override fun extractId(entity: AttendanceLog): Long = entity.id

        override fun assignId(entity: AttendanceLog): AttendanceLog {
            if (entity.id != 0L) return entity
            val idField = AttendanceLog::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
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
}
