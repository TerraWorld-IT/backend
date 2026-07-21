package com.terraworld.api.record

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.notification.FriendActivityEvent
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTracker
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.UserRepository
import org.springframework.context.ApplicationEventPublisher
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
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
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
        val saved =
            habitTrackerRepository.save(
                HabitTracker(
                    userId = userId,
                    title = title.trim(),
                    startDate = KstTime.today(),
                    friendLinkId = resolvedLinkId,
                ),
            )

        // 친구 연동 습관 생성 — 상대에게 즉시 알림 (2026-07-21 사용자 리포트: 생성해도 상대가
        // 몰라 "연동되는 부분이 없음"으로 체감). 상대가 같은 친구로 습관을 만들면 같은
        // friendLinkId 를 공유해 lockstep 2배가 작동한다 — 이 푸시가 그 참여를 유도한다.
        // AFTER_COMMIT listener 라 저장 롤백 시 발송되지 않는다.
        if (resolvedLinkId != null && !friendUserId.isNullOrBlank()) {
            val nickname = userRepository.findById(userId).map { it.nickname }.orElse("친구")
            eventPublisher.publishEvent(
                FriendActivityEvent(
                    fromUserId = userId,
                    toUserId = friendUserId,
                    message = "$nickname 님이 「${saved.title}」 습관을 함께 시작했어요! 기록하기에서 함께 해보세요",
                ),
            )
        }
        return saved
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
            // Codex R1 #1: 상대 트래커는 ACTIVE 만 인정 — stop() 신설로 BROKEN 잔존 트래커가
            // 생기는데, 상태 무관 매칭이면 "중단 후 재생성 → 상대의 죽은 cycle=N 과 매칭" 으로
            // 첫 완주마다 2배를 반복 수취할 수 있다. partnerActive(resolveFriendMeta) 판정과도 정합.
            val doubled =
                tracker.friendLinkId?.let { linkId ->
                    habitTrackerRepository
                        .findAllByFriendLinkId(linkId)
                        .any {
                            it.userId != userId &&
                                it.status == HabitStatus.ACTIVE &&
                                it.completedCycles == tracker.completedCycles
                        }
                } ?: false
            sparkleGranted = if (doubled) SPARKLE_PER_CYCLE * 2 else SPARKLE_PER_CYCLE
            currencyService.credit(userId, CurrencyCode.SPARKLE, sparkleGranted, REASON_HABIT_CYCLE, REF_TYPE, trackerId.toString())
        }

        habitTrackerRepository.save(tracker)

        // 친구 연동 습관 — 체크인/완주를 상대에게 알림 (낙서장 "트래커 기록 시 상대 알림").
        // 같은 날 재체크인은 위에서 조기 return 이라 알림 최대 1회/일. 실패해도 체크인은 유지
        // (listener 가 @Async 라 도메인 tx 와 분리 — InviteService N8 과 동일 패턴).
        tracker.friendLinkId?.let { linkId ->
            val partnerId = resolvePartnerUserId(userId, linkId)
            if (partnerId != null) {
                val nickname = userRepository.findById(userId).map { it.nickname }.orElse("친구")
                val message =
                    if (cycleCompleted) {
                        "$nickname 님이 「${tracker.title}」 습관 ${tracker.cycleLengthDays}일을 완주했어요! 🎉"
                    } else {
                        "$nickname 님이 「${tracker.title}」 습관을 체크인했어요 (${tracker.currentStreakDays}일째)"
                    }
                eventPublisher.publishEvent(
                    FriendActivityEvent(fromUserId = userId, toUserId = partnerId, message = message),
                )
            }
        }

        return HabitCheckInResult(tracker, cycleCompleted, sparkleGranted)
    }

    /**
     * 습관 트래커 중단 — status 를 BROKEN 으로 전환한다 (M1: 종료 수단 부재 해소).
     * 같은 친구쌍 활성 1개 제한(createTracker)은 ACTIVE 만 검사하므로, 중단 후
     * 같은 친구와 새 습관을 만들 수 있게 된다. 이미 지급된 반짝이는 회수하지 않는다.
     * 이미 종료된 트래커의 재중단은 멱등 no-op.
     */
    @Transactional
    fun stop(
        userId: String,
        trackerId: Long,
    ) {
        // Codex R1 #8: read-then-save 는 동시 DELETE 시 @Version 낙관락 충돌로 한쪽이 500 —
        // 조건부 UPDATE 로 전환해 동시에도 멱등 (0건이면 "이미 종료" vs "미존재/타인" 만 구분).
        val updated = habitTrackerRepository.markBroken(trackerId, userId)
        if (updated == 0 && habitTrackerRepository.findByIdAndUserId(trackerId, userId) == null) {
            throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        }
    }

    /**
     * 응답용 친구 메타 배치 해석 (M2: 상대 참여 여부 미노출 해소, N+1 회피).
     * 링크된 트래커마다 상대 userId/닉네임과 "상대도 같은 링크로 ACTIVE 트래커를 만들었는지"
     * (partnerActive — true 일 때만 완주 2배 성립)를 trackerId 키로 반환한다.
     */
    @Transactional(readOnly = true)
    fun resolveFriendMeta(
        userId: String,
        trackers: List<HabitTracker>,
    ): Map<Long, HabitFriendMeta> {
        val linkIds = trackers.mapNotNull { it.friendLinkId }.distinct()
        if (linkIds.isEmpty()) return emptyMap()

        // Codex R1 #9: 내가 inviter 도 invitee 도 아닌 malformed/legacy 링크는 null —
        // "inviter 아니면 무조건 invitee" 가정은 무관한 사용자 ID/닉네임을 노출할 수 있다.
        val partnerIdByLink =
            inviteRepository
                .findAllById(linkIds)
                .associate { invite ->
                    invite.id to
                        when (userId) {
                            invite.inviterUserId -> invite.inviteeUserId
                            invite.inviteeUserId -> invite.inviterUserId
                            else -> null
                        }
                }
        val partnerTrackersByLink =
            habitTrackerRepository
                .findAllByFriendLinkIdIn(linkIds)
                .filter { it.status == HabitStatus.ACTIVE }
                .groupBy { it.friendLinkId }
        val nicknameById =
            userRepository
                .findAllById(partnerIdByLink.values.filterNotNull().distinct())
                .associate { it.id to it.nickname }

        return trackers
            .filter { it.friendLinkId != null }
            .associate { tracker ->
                val linkId = tracker.friendLinkId!!
                val partnerId = partnerIdByLink[linkId]
                tracker.id to
                    HabitFriendMeta(
                        friendUserId = partnerId,
                        friendNickname = partnerId?.let { nicknameById[it] },
                        // Codex R1 #9: "나 아닌 아무나" 가 아니라 해석된 상대 본인의 ACTIVE 트래커만.
                        partnerActive =
                            partnerId != null &&
                                partnerTrackersByLink[linkId].orEmpty().any { it.userId == partnerId },
                    )
            }
    }

    /**
     * friendLinkId(=수락된 invite.id)에서 상대 userId 해석. 해석 불가 시 null(알림 skip).
     * 내가 어느 쪽도 아닌 malformed 링크도 null (Codex R1 #9 — 무관 사용자 노출 방지).
     */
    private fun resolvePartnerUserId(
        userId: String,
        linkId: Long,
    ): String? =
        inviteRepository
            .findById(linkId)
            .map { invite ->
                when (userId) {
                    invite.inviterUserId -> invite.inviteeUserId
                    invite.inviteeUserId -> invite.inviterUserId
                    else -> null
                }
            }.orElse(null)
}

data class HabitFriendMeta(
    val friendUserId: String?,
    val friendNickname: String?,
    val partnerActive: Boolean,
)

data class HabitCheckInResult(
    val tracker: HabitTracker,
    val cycleCompleted: Boolean,
    val sparkleGranted: Long,
)
