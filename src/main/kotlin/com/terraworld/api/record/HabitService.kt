package com.terraworld.api.record

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.InviteRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 습관(7일 트래커) 서비스 (낙서장 P1). 연속 체크인 → cycle(7일) 완료 시 반짝이 지급.
 * 친구 연동(friendLinkId) + 같은 cycle 양측 완료 시 2배. 반짝이는 CurrencyService.credit(SPARKLE).
 * 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md
 */
@Service
class HabitService(
    private val habitTrackerRepository: HabitTrackerRepository,
    private val currencyService: CurrencyService,
    private val inviteRepository: InviteRepository,
) {
    companion object {
        const val SPARKLE_PER_CYCLE = 120L
        const val REASON_HABIT_CYCLE = "HABIT_CYCLE"
        const val REF_TYPE = "HABIT"
    }

    @Transactional
    fun createTracker(
        userId: String,
        title: String,
        friendUserId: String? = null,
    ): HabitTracker {
        require(title.isNotBlank()) { "title 은 공백 불가" }
        // 친구 함께 습관(req3 #3): friendUserId 를 수락된 invite 로 검증해 연동 ID(invite.id)로 해석.
        // 양측이 서로를 friendUserId 로 지정하면 방향 무관 같은 invite.id → friendLinkId 공유 → cycle 완주 2배.
        // Codex #3 H#1: 클라가 friendLinkId 를 직접 지정하는 경로는 폐지 — friendUserId(수락 invite 검증)만 신뢰.
        //   (무검증 임의 link 저장 시 비친구 2배 수취 우회 가능했음.)
        val resolvedLinkId: Long? =
            if (!friendUserId.isNullOrBlank()) {
                val linkId =
                    inviteRepository
                        .findAcceptedBetween(userId, friendUserId)
                        .map { it.id }
                        .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT, "수락된 친구 관계가 아닙니다") }
                // R8: check+insert 를 per-(userId|linkId) tx-scoped advisory lock 으로 직렬화 — read-check 만으로는
                //   동시 POST /habits 가 둘 다 alreadyActive=false 를 보고 중복 활성 tracker 를 만들어 SPARKLE 2배
                //   이중 판정이 가능(V31 에 unique 부재). lock 을 check 직전 획득해 방지.
                habitTrackerRepository.acquireHabitPairLock("habit|$userId|$linkId")
                // Codex #3 H#2: 같은 친구쌍에 활성 공동 습관 1개로 제한 — N개 생성해 상대 1완료로 N배 중복 수취 방지.
                val alreadyActive =
                    habitTrackerRepository
                        .findAllByFriendLinkId(linkId)
                        .any { it.userId == userId && it.status == HabitStatus.ACTIVE }
                if (alreadyActive) throw BusinessException(ErrorCode.INVALID_INPUT, "이미 이 친구와 함께하는 습관이 있어요")
                linkId
            } else {
                null
            }
        return habitTrackerRepository.save(
            HabitTracker(
                userId = userId,
                title = title.trim(),
                startDate = KstTime.today(),
                friendLinkId = resolvedLinkId,
            ),
        )
    }

    @Transactional(readOnly = true)
    fun listActive(userId: String): List<HabitTracker> = habitTrackerRepository.findAllByUserIdAndStatus(userId, HabitStatus.ACTIVE)

    /**
     * 오늘 체크인. 연속이면 streak++, 하루 이상 공백이면 streak=1 재시작.
     * cycle(cycleLengthDays) 도달 시 completedCycles++ + streak 리셋 + 반짝이 지급(친구 완료 시 2배).
     * 같은 날 재체크인은 멱등 no-op(중복 지급 방지).
     */
    @Transactional
    fun checkIn(
        userId: String,
        trackerId: Long,
    ): HabitCheckInResult {
        val tracker =
            habitTrackerRepository.findByIdAndUserId(trackerId, userId)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (tracker.status != HabitStatus.ACTIVE) throw BusinessException(ErrorCode.INVALID_INPUT, "종료된 트래커입니다")

        val today = KstTime.today()
        if (tracker.lastCheckedDate == today) {
            // 같은 날 재체크인 — 멱등
            return HabitCheckInResult(tracker, cycleCompleted = false, sparkleGranted = 0)
        }

        // 연속 판정: 어제 체크인했으면 streak++, 아니면(공백/최초) 1 로 재시작
        tracker.currentStreakDays =
            if (tracker.lastCheckedDate == today.minusDays(1)) tracker.currentStreakDays + 1 else 1
        tracker.lastCheckedDate = today

        var cycleCompleted = false
        var sparkleGranted = 0L
        if (tracker.currentStreakDays >= tracker.cycleLengthDays) {
            cycleCompleted = true
            tracker.completedCycles += 1
            tracker.currentStreakDays = 0

            // 친구 연동 + 같은 cycle 양측 완료 시 2배 (`==` lockstep — 상대가 앞선 cycle이면 다른 window라 제외).
            // 알려진 한계(Codex #3 M#3/M#4 — pair-cycle ledger 설계 후속): (1) completedCycles equality 만 보고
            //   실제 완료 시각 window 를 비교하지 않아 서로 다른 시기의 동일 cycle 도 매칭될 수 있음. (2) 양측 동시
            //   완주 시 미커밋 상태를 못 봐 2배가 비결정적(첫 완료자 손해 가능). 완전 해소는 pair-cycle 정산 ledger +
            //   양측 tracker 직렬화 필요. prototype 한정 영향(친구쌍당 활성 습관 1개 제한 후 최대 1 cycle 오차, in-game SPARKLE).
            val doubled =
                tracker.friendLinkId?.let { linkId ->
                    habitTrackerRepository
                        .findAllByFriendLinkId(linkId)
                        .any { it.userId != userId && it.completedCycles == tracker.completedCycles }
                } ?: false
            sparkleGranted = if (doubled) SPARKLE_PER_CYCLE * 2 else SPARKLE_PER_CYCLE
            currencyService.credit(userId, CurrencyCode.SPARKLE, sparkleGranted, REASON_HABIT_CYCLE, REF_TYPE, trackerId.toString())
        }

        habitTrackerRepository.save(tracker)
        return HabitCheckInResult(tracker, cycleCompleted, sparkleGranted)
    }
}

data class HabitCheckInResult(
    val tracker: HabitTracker,
    val cycleCompleted: Boolean,
    val sparkleGranted: Long,
)
