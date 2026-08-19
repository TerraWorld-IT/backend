package com.terraworld.api.record

import com.terraworld.api.notification.HabitCheerEvent
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.HabitCheer
import com.terraworld.domain.record.HabitCheerRepository
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.UserRepository
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 습관 응원 서비스 (apjek social loop).
 *
 * 친구 연동(friendLinkId) 습관 트래커에 하루 3회/트래커까지 응원 메시지를 보낸다.
 * 상대 해석은 HabitService.resolveFriendMeta 와 동일 규칙 — friendLinkId(=수락된 invite.id)의
 * inviter/invitee 중 발신자의 반대편. 발신자가 어느 쪽도 아니면 참여자가 아니므로 거부한다.
 *
 * 효과 2종은 이벤트로 분리 발행: 푸시(FcmEventListener.onHabitCheer) + 알림함
 * append(NotificationEventListener.onHabitCheer). 둘 다 @Async AFTER_COMMIT 이라
 * 본 tx 롤백 시 발송되지 않고, 발송 실패가 응원 기록을 롤백하지도 않는다 (도메인 tx 분리).
 */
@Service
class HabitCheerService(
    private val habitTrackerRepository: HabitTrackerRepository,
    private val habitCheerRepository: HabitCheerRepository,
    private val inviteRepository: InviteRepository,
    private val userRepository: UserRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    companion object {
        /** 트래커당 일일 응원 한도 (KST 날짜 기준). */
        const val DAILY_CHEER_LIMIT = 3
        const val MAX_MESSAGE_LENGTH = 100
    }

    @Transactional
    fun cheer(
        userId: String,
        trackerId: Long,
        message: String,
    ): HabitCheer {
        val trimmed = message.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_MESSAGE_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "응원 메시지는 1~${MAX_MESSAGE_LENGTH}자여야 합니다")
        }

        val tracker =
            habitTrackerRepository
                .findById(trackerId)
                .orElseThrow { BusinessException(ErrorCode.HABIT_NOT_FOUND) }

        // spec: 비참여자 404(존재 숨김) / solo 409. 비참여자에게 409 를 주면 솔로 습관 존재가 드러난다.
        val linkId = tracker.friendLinkId
        if (linkId == null) {
            if (tracker.userId != userId) throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
            throw BusinessException(ErrorCode.NOT_FRIEND_LINKED)
        }
        val partnerId =
            inviteRepository
                .findById(linkId)
                .map { invite ->
                    when (userId) {
                        invite.inviterUserId -> invite.inviteeUserId
                        invite.inviteeUserId -> invite.inviterUserId
                        else -> null
                    }
                }.orElse(null)
                ?: throw BusinessException(ErrorCode.HABIT_NOT_FOUND)
        if (tracker.status != HabitStatus.ACTIVE) {
            throw BusinessException(ErrorCode.HABIT_NOT_ACTIVE)
        }

        // 일 3회/트래커 제한 — count-then-insert 의 TOCTOU 를 per-(발신자|트래커) advisory lock 으로 직렬화
        // (HabitService.createTracker R8 패턴). 날짜는 KST 기준 (KstTime 관례).
        habitCheerRepository.acquireCheerLock("cheer|$userId|$trackerId")
        val today = KstTime.today()
        val sentToday = habitCheerRepository.countByFromUserIdAndTrackerIdAndCheeredDate(userId, trackerId, today)
        if (sentToday >= DAILY_CHEER_LIMIT) {
            throw BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED, "이 습관에는 오늘 응원을 모두 보냈어요 (${DAILY_CHEER_LIMIT}회/일)")
        }

        val saved =
            habitCheerRepository.save(
                HabitCheer(
                    trackerId = trackerId,
                    fromUserId = userId,
                    toUserId = partnerId,
                    message = trimmed,
                    cheeredDate = today,
                ),
            )

        // 푸시 + 알림함 — 커밋 확정 후 각 listener 가 처리 (본 tx 롤백 시 미발송).
        val nickname = userRepository.findById(userId).map { it.nickname }.orElse("친구")
        eventPublisher.publishEvent(
            HabitCheerEvent(
                fromUserId = userId,
                toUserId = partnerId,
                fromNickname = nickname,
                message = trimmed,
            ),
        )
        return saved
    }
}
