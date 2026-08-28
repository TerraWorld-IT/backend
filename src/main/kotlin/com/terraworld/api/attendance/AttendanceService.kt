package com.terraworld.api.attendance

import com.terraworld.api.notification.AttendanceCycleCompletedEvent
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.attendance.AttendanceLog
import com.terraworld.domain.attendance.AttendanceLogRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.AttendanceBoardDay
import io.terraworld.api.model.AttendanceCheckInResponse
import io.terraworld.api.model.AttendanceCheckInResponseReward
import io.terraworld.api.model.AttendanceResponse
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.ZoneOffset

// NOTE: HTTP 진입점은 com.terraworld.api.reward.RewardController 로 이전됐다
// (generated RewardApi 채택). 본 파일은 service 만 보존한다.

/**
 * 출석 (아프젝 v2 §R4 — 7일 사이클 보드).
 *
 * - streak 규칙은 종전과 동일: 마지막 출석이 어제면 유지(+1), 하루라도 누락이면 0(→1) — 누락 시 보드 초기화.
 * - 사이클 일차 `cycleDay`: 체크인 후(today=true) = `((streak-1)%7)+1`, 체크인 전 = `(streak%7)+1`.
 * - 보상표는 설정([AttendanceProperties]): 일차별 기본 코인 + 7일차 루비 보너스. 7일차 중복 POST 는 409 유지.
 * - 보드 `claimed/claimedAt` 은 이번 사이클 1일차 KST 일자부터의 출석 로그로 파생(별도 테이블 없음).
 */
@Service
class AttendanceService(
    private val userRepository: UserRepository,
    // 낙서장 P1 cutover(dual-write): 정규화 잔액에도 반영 (WalletBuilder switch 후 old 제거)
    private val currencyService: com.terraworld.api.currency.CurrencyService,
    private val attendanceRepository: AttendanceLogRepository,
    private val walletBuilder: WalletBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 출석 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: AttendanceProperties,
) {
    companion object {
        const val CYCLE_DAYS = AttendanceProperties.CYCLE_DAYS
        const val REF_TYPE = "ATTENDANCE"
    }

    @Transactional(readOnly = true)
    fun getState(userId: String): AttendanceResponse {
        val today = KstTime.today()
        val todayLog = attendanceRepository.findByUserIdAndCheckInDate(userId, today)
        val (currentStreak, _) = computeNextStreak(userId)
        return buildState(userId, today, todayChecked = todayLog.isPresent, streak = currentStreak)
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
        val cycleDay = cycleDayAfterCheckIn(newStreak)
        val bonus = cycleDay == CYCLE_DAYS
        val reward = properties.coinsForDay(cycleDay)
        val rubyBonus = if (bonus) properties.cycleBonusRuby else 0

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

        // 낙서장 P1: 출석 보상 = 신 substrate COIN credit 단일 SoT (credit 이 잔액+원장 원자 처리)
        currencyService.credit(
            userId,
            com.terraworld.domain.currency.CurrencyCode.COIN,
            reward.toLong(),
            com.terraworld.api.wallet.WalletTransactionService.REASON_ATTENDANCE,
            REF_TYPE,
            attendanceLog.id.toString(),
        )
        // 아프젝 v2: 7일차 사이클 보너스 루비 (같은 tx — 코인과 함께 원자 지급)
        if (rubyBonus > 0) {
            currencyService.credit(
                userId,
                com.terraworld.domain.currency.CurrencyCode.RUBY,
                rubyBonus.toLong(),
                com.terraworld.api.wallet.WalletTransactionService.REASON_ATTENDANCE,
                REF_TYPE,
                attendanceLog.id.toString(),
            )
        }

        auditService.publish(
            userId = userId,
            action = "ATTENDANCE_CHECKIN",
            resourceType = "Attendance",
            payload =
                mapOf(
                    "streak" to newStreak,
                    "cycleDay" to cycleDay,
                    "rewardBasicCoins" to reward,
                    "bonus" to bonus,
                    "rubyBonus" to rubyBonus,
                ),
        )

        // 7일차 지급이 포함된 트랜잭션이 커밋된 뒤 알림함에 SYSTEM 알림을 한 번만 적재한다.
        if (bonus) {
            eventPublisher.publishEvent(AttendanceCycleCompletedEvent(userId))
        }

        return AttendanceCheckInResponse(
            reward = AttendanceCheckInResponseReward(basicCoins = reward, bonus = bonus, rubyBonus = rubyBonus),
            attendance = buildState(userId, today, todayChecked = true, streak = newStreak),
            currency = walletBuilder.build(userId, user),
        )
    }

    /** 체크인 후 일차: ((streak-1) % 7) + 1. */
    private fun cycleDayAfterCheckIn(streak: Int): Int = ((streak - 1) % CYCLE_DAYS) + 1

    /**
     * 상태 응답 조립. [streak] 은 체크인 반영 후 값(오늘 체크인했으면 오늘 포함, 아니면 어제까지 — 누락 시 0).
     * - cycleDay: today=true → 오늘 일차, today=false → 다음 일차.
     * - cycleStart: 이번 사이클 1일차 일자. 이번 사이클에 체크인이 하나도 없으면(streak 0 또는 어제 7일차 완료) null.
     * - board: cycleStart 부터의 출석 로그로 claimed/claimedAt 파생.
     */
    private fun buildState(
        userId: String,
        today: LocalDate,
        todayChecked: Boolean,
        streak: Int,
    ): AttendanceResponse {
        val cycleDay = if (todayChecked) cycleDayAfterCheckIn(streak) else (streak % CYCLE_DAYS) + 1
        // 이번 사이클에서 이미 채운 칸 수: 체크인 후 = 오늘 일차, 체크인 전 = 다음 일차 - 1
        val filledDays = if (todayChecked) cycleDay else cycleDay - 1
        val lastFilledDate = if (todayChecked) today else today.minusDays(1)
        val cycleStart: LocalDate? = if (filledDays > 0) lastFilledDate.minusDays((filledDays - 1).toLong()) else null

        val logsByDate =
            if (cycleStart != null) {
                attendanceRepository
                    .findAllByUserIdAndCheckInDateBetween(userId, cycleStart, lastFilledDate)
                    .associateBy { it.checkInDate }
            } else {
                emptyMap()
            }

        val board =
            (1..CYCLE_DAYS).map { day ->
                val log = cycleStart?.let { logsByDate[it.plusDays((day - 1).toLong())] }
                AttendanceBoardDay(
                    day = day,
                    rewardBasicCoins = properties.coinsForDay(day),
                    claimed = log != null,
                    claimedAt = log?.createdAt?.atOffset(ZoneOffset.UTC),
                )
            }
        val bonusEligible = cycleDay == CYCLE_DAYS
        return AttendanceResponse(
            today = todayChecked,
            streak = streak,
            longestStreak = attendanceRepository.findLongestStreak(userId),
            rewardBasicCoins = properties.coinsForDay(cycleDay),
            serverDateKst = today,
            cycleDay = cycleDay,
            board = board,
            cycleBonusRuby = properties.cycleBonusRuby,
            cycleBonusClaimed = todayChecked && bonusEligible,
            bonusEligible = bonusEligible,
            cycleStartDateKst = cycleStart,
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
