package com.terraworld.api.attendance

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.attendance.AttendanceLog
import com.terraworld.domain.attendance.AttendanceLogRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDate

@Tag(name = "Reward", description = "출석 체크인 (이슬 보상)")
@RestController
@RequestMapping("/api/v1/rewards/attendance")
class AttendanceController(
    private val attendanceService: AttendanceService,
) {
    @Operation(summary = "출석 현황 조회")
    @GetMapping
    fun getState(): AttendanceStateResponse = attendanceService.getState(SecurityUtil.getCurrentUserId())

    @Operation(summary = "출석 체크인")
    @PostMapping
    fun checkIn(): AttendanceCheckInResponse = attendanceService.checkIn(SecurityUtil.getCurrentUserId())
}

@Service
class AttendanceService(
    private val userRepository: UserRepository,
    private val attendanceRepository: AttendanceLogRepository,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
) {
    companion object {
        const val BASE_REWARD = 5
        const val BONUS_REWARD = 20
        const val BONUS_INTERVAL = 7
    }

    @Transactional(readOnly = true)
    fun getState(userId: String): AttendanceStateResponse {
        val today = LocalDate.now()
        val todayLog = attendanceRepository.findByUserIdAndCheckInDate(userId, today)
        val (currentStreak, _) = computeNextStreak(userId)
        val nextStreak = if (todayLog.isPresent) currentStreak else currentStreak + 1
        val bonusEligible = nextStreak > 0 && nextStreak % BONUS_INTERVAL == 0
        return AttendanceStateResponse(
            today = todayLog.isPresent,
            streak = currentStreak,
            longestStreak = attendanceRepository.findLongestStreak(userId),
            rewardBasicCoins = if (bonusEligible) BONUS_REWARD else BASE_REWARD,
            bonusEligible = bonusEligible,
        )
    }

    @Transactional
    fun checkIn(userId: String): AttendanceCheckInResponse {
        val user =
            userRepository.findById(userId).orElseThrow {
                BusinessException(ErrorCode.USER_NOT_FOUND)
            }
        val today = LocalDate.now()

        if (attendanceRepository.findByUserIdAndCheckInDate(userId, today).isPresent) {
            throw BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED)
        }

        val (previousStreak, _) = computeNextStreak(userId)
        val newStreak = previousStreak + 1
        val bonus = newStreak % BONUS_INTERVAL == 0
        val reward = if (bonus) BONUS_REWARD else BASE_REWARD

        attendanceRepository.save(
            AttendanceLog(
                userId = userId,
                checkInDate = today,
                streak = newStreak,
                rewardBasicCoins = reward,
                bonus = bonus,
            ),
        )

        user.basicCoin += reward
        userRepository.save(user)

        auditService.publish(
            userId = userId,
            action = "ATTENDANCE_CHECKIN",
            resourceType = "Attendance",
            payload =
                mapOf(
                    "streak" to newStreak,
                    "rewardBasicCoins" to reward,
                    "bonus" to bonus,
                ),
        )

        return AttendanceCheckInResponse(
            reward = AttendanceRewardInfo(basicCoins = reward, bonus = bonus),
            attendance =
                AttendanceStateResponse(
                    today = true,
                    streak = newStreak,
                    longestStreak = attendanceRepository.findLongestStreak(userId),
                    rewardBasicCoins = if ((newStreak + 1) % BONUS_INTERVAL == 0) BONUS_REWARD else BASE_REWARD,
                    bonusEligible = (newStreak + 1) % BONUS_INTERVAL == 0,
                ),
            currency = currencyBuilder.build(userId, user),
        )
    }

    /**
     * 마지막 출석일이 어제이면 streak 유지(오늘 체크인 시 +1), 그렇지 않으면 0 (오늘 체크인 시 1).
     */
    private fun computeNextStreak(userId: String): Pair<Int, LocalDate?> {
        val latest = attendanceRepository.findLatestByUserId(userId)
        if (!latest.isPresent) return 0 to null
        val log = latest.get()
        val today = LocalDate.now()
        return when {
            log.checkInDate == today -> log.streak to log.checkInDate
            log.checkInDate == today.minusDays(1) -> log.streak to log.checkInDate
            else -> 0 to log.checkInDate
        }
    }
}

data class AttendanceStateResponse(
    val today: Boolean,
    val streak: Int,
    val longestStreak: Int,
    val rewardBasicCoins: Int,
    val bonusEligible: Boolean? = null,
)

data class AttendanceRewardInfo(
    val basicCoins: Int,
    val bonus: Boolean,
)

data class AttendanceCheckInResponse(
    val reward: AttendanceRewardInfo,
    val attendance: AttendanceStateResponse,
    val currency: CurrencyResponse,
)
