package com.terraworld.api.attendance

import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.attendance.AttendanceLog
import com.terraworld.domain.attendance.AttendanceLogRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.AttendanceCheckInResponse
import io.terraworld.api.model.AttendanceCheckInResponseReward
import io.terraworld.api.model.AttendanceResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

// NOTE: HTTP 진입점은 com.terraworld.api.reward.RewardController 로 이전됐다
// (generated RewardApi 채택). 본 파일은 service 만 보존한다.
//
// ARCH-008-phase-6: 반환 타입을 generated DTO 직접 사용. local DTO 제거.
// (이전 file 이름 AttendanceController.kt 그대로 유지 — 추후 AttendanceService.kt 로 rename 예정.)

@Service
class AttendanceService(
    private val userRepository: UserRepository,
    private val attendanceRepository: AttendanceLogRepository,
    private val walletBuilder: WalletBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 출석 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
) {
    companion object {
        const val BASE_REWARD = 5
        const val BONUS_REWARD = 20
        const val BONUS_INTERVAL = 7
    }

    @Transactional(readOnly = true)
    fun getState(userId: String): AttendanceResponse {
        val today = KstTime.today()
        val todayLog = attendanceRepository.findByUserIdAndCheckInDate(userId, today)
        val (currentStreak, _) = computeNextStreak(userId)
        val nextStreak = if (todayLog.isPresent) currentStreak else currentStreak + 1
        val bonusEligible = nextStreak > 0 && nextStreak % BONUS_INTERVAL == 0
        return AttendanceResponse(
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
        val today = KstTime.today()

        if (attendanceRepository.findByUserIdAndCheckInDate(userId, today).isPresent) {
            throw BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED)
        }

        val (previousStreak, _) = computeNextStreak(userId)
        val newStreak = previousStreak + 1
        val bonus = newStreak % BONUS_INTERVAL == 0
        val reward = if (bonus) BONUS_REWARD else BASE_REWARD

        val attendanceLog =
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

        // N2: wallet ledger — BASIC_COIN row
        walletTransactionService.append(
            user = user,
            currencyType = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_BASIC_COIN,
            amount = reward.toLong(),
            balanceAfter = user.basicCoin,
            reason = com.terraworld.api.wallet.WalletTransactionService.REASON_ATTENDANCE,
            referenceId = attendanceLog.id,
        )

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
            reward = AttendanceCheckInResponseReward(basicCoins = reward, bonus = bonus),
            attendance =
                AttendanceResponse(
                    today = true,
                    streak = newStreak,
                    longestStreak = attendanceRepository.findLongestStreak(userId),
                    rewardBasicCoins = if ((newStreak + 1) % BONUS_INTERVAL == 0) BONUS_REWARD else BASE_REWARD,
                    bonusEligible = (newStreak + 1) % BONUS_INTERVAL == 0,
                ),
            currency = walletBuilder.build(userId, user),
        )
    }

    /**
     * 마지막 출석일이 어제이면 streak 유지(오늘 체크인 시 +1), 그렇지 않으면 0 (오늘 체크인 시 1).
     */
    private fun computeNextStreak(userId: String): Pair<Int, LocalDate?> {
        val latest = attendanceRepository.findLatestByUserId(userId)
        if (!latest.isPresent) return 0 to null
        val log = latest.get()
        val today = KstTime.today()
        return when {
            log.checkInDate == today -> log.streak to log.checkInDate
            log.checkInDate == today.minusDays(1) -> log.streak to log.checkInDate
            else -> 0 to log.checkInDate
        }
    }
}
