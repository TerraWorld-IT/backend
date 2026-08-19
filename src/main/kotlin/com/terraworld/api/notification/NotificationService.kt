package com.terraworld.api.notification

import com.terraworld.domain.notification.NotificationType
import com.terraworld.domain.notification.UserNotification
import com.terraworld.domain.notification.UserNotificationRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.util.UUID

/**
 * 인앱 알림함 서비스 (apjek social loop, V38).
 *
 * [append] 는 FCM 발송(FcmEventListener)과 **별개의 저장 경로** — 푸시가 실패해도 알림함에는
 * 남고, 알림함 저장이 실패해도 푸시에는 영향 없다. 도메인 이벤트 연결(친구 활동/결제/응원)은
 * [NotificationEventListener] 가 커밋 확정 후(@Async AFTER_COMMIT) 본 서비스를 호출한다.
 */
@Service
class NotificationService(
    private val userNotificationRepository: UserNotificationRepository,
) {
    /**
     * 알림 1건 저장. 호출 스레드의 tx 에 참여하고, 리스너(@Async) 경유 시엔 새 tx 로 커밋된다.
     * title/body 는 컬럼 길이(100/500)에 맞춰 방어적으로 절단 — 이벤트 메시지가 사용자 입력
     * (닉네임/습관 제목)을 포함해 조립되므로 길이 초과가 저장 실패로 번지지 않게 한다.
     */
    @Transactional
    fun append(
        userId: String,
        type: NotificationType,
        title: String,
        body: String,
        route: String? = null,
    ): UserNotification =
        userNotificationRepository.save(
            UserNotification(
                userId = userId,
                type = type,
                title = title.take(MAX_TITLE_LENGTH),
                body = body.take(MAX_BODY_LENGTH),
                route = route?.take(MAX_ROUTE_LENGTH),
            ),
        )

    /** 알림함 목록 — 최신순 페이지 조회. */
    @Transactional(readOnly = true)
    fun list(
        userId: String,
        pageable: Pageable,
    ): Page<UserNotification> = userNotificationRepository.findAllByUserIdOrderByCreatedAtDesc(userId, pageable)

    /** 미읽음 카운트 — 뱃지 표시용. */
    @Transactional(readOnly = true)
    fun unreadCount(userId: String): Long = userNotificationRepository.countByUserIdAndReadAtIsNull(userId)

    /**
     * 읽음 처리. ids 가 비어 있으면 **전체 읽음** — API 스펙 확정안의 계약.
     * user_id 스코프로 걸어 타인 알림은 건드리지 않는다 (모르는 id 는 조용히 무시 — 멱등).
     *
     * @return 이번 호출로 읽음 전환된 건수
     */
    @Transactional
    fun markRead(
        userId: String,
        ids: List<UUID>,
    ): Int {
        val now = LocalDateTime.now()
        return if (ids.isEmpty()) {
            userNotificationRepository.markAllRead(userId, now)
        } else {
            userNotificationRepository.markRead(userId, ids, now)
        }
    }

    companion object {
        const val MAX_TITLE_LENGTH = 100
        const val MAX_BODY_LENGTH = 500
        const val MAX_ROUTE_LENGTH = 200
    }
}
