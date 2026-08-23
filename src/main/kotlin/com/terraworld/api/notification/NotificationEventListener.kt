package com.terraworld.api.notification

import com.terraworld.domain.notification.NotificationType
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 인앱 알림함 append 트리거 — 도메인 event listener (apjek social loop, V38).
 *
 * [FcmEventListener](푸시 발송)와 **별개의 저장 경로**: 같은 도메인 이벤트를 각자 수신해
 * 푸시는 FCM 으로, 영속 기록은 user_notifications 로 나간다. 한쪽 실패가 다른 쪽에 영향 없음.
 *
 * 처리 event:
 *  1) [FriendActivityEvent]: 친구 신규 기록 / invite 수락 / 습관 함께 시작·체크인 → FRIEND_JOINED
 *  2) [PaymentCompletedEvent]: IAP verify / RTDN webhook 의 결제 승인 확정(grant) → PAYMENT
 *  3) [HabitCheerEvent]: 습관 응원 수신 → CHEER
 *
 * 본 listener 는 FcmEventListener 와 동일하게 `@Async` + `@TransactionalEventListener(AFTER_COMMIT,
 * fallbackExecution=true)` — 알림함 저장 실패가 도메인 tx rollback 을 유발하지 않고, **커밋 확정
 * 후에만** 기록한다 (롤백된 활동의 알림이 남는 것 방지). AFTER_COMMIT 시점의 DB 쓰기는 원 tx 에
 * 참여하면 커밋되지 않으므로, @Async 로 스레드를 분리해 [NotificationService.append] 의
 * @Transactional 이 새 tx 를 열게 한다 (도메인 tx 분리).
 *
 * executor 는 fcmExecutor 공유 — 같은 notification 도메인이고 append 는 짧은 단건 INSERT 라
 * FCM burst 처리량에 유의미한 점유가 없다 (FcmAsyncConfig 의 타 도메인 분리 권고는 AuditService
 * 류 고빈도 도메인 대상).
 */
@Component
class NotificationEventListener(
    private val notificationService: NotificationService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onFriendActivity(event: FriendActivityEvent) {
        runCatching {
            notificationService.append(
                userId = event.toUserId,
                type = NotificationType.FRIEND_JOINED,
                // FcmEventListener.onFriendActivity 의 푸시 title/body 와 동일 카피 — 알림함과 푸시 일치.
                title = "👯 친구가 활동했어요",
                body = event.message,
                route = event.route,
            )
        }.onFailure { log.warn("notification.append.fail type=FRIEND to={} — {}", event.toUserId, it.message) }
    }

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onPaymentCompleted(event: PaymentCompletedEvent) {
        runCatching {
            notificationService.append(
                userId = event.userId,
                type = NotificationType.PAYMENT,
                title = "💳 결제 완료",
                body = "구매하신 상품이 정상 적용되었어요",
                route = null,
            )
        }.onFailure { log.warn("notification.append.fail type=PAYMENT to={} — {}", event.userId, it.message) }
    }

    /** 아프젝 v2: 친구 습관 요청 도착/수락/거절·취소/상대 중단/연장 요청 → HABIT (route /record). */
    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onHabitPair(event: HabitPairEvent) {
        runCatching {
            notificationService.append(
                userId = event.toUserId,
                type = NotificationType.HABIT,
                title = event.title,
                body = event.body,
                route = event.route,
            )
        }.onFailure { log.warn("notification.append.fail type=HABIT to={} — {}", event.toUserId, it.message) }
    }

    /** 아프젝 v2: 키우기 완료 사이클 리셋 시 notifyNext 신청 유저에게 1회 → SPIRIT_ARRIVED (route /grow). */
    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onSpiritArrived(event: SpiritArrivedEvent) {
        runCatching {
            notificationService.append(
                userId = event.userId,
                type = NotificationType.SPIRIT_ARRIVED,
                title = "✨ 새 정령이 도착했어요",
                body = "${event.speciesNameKo} 의 새 사이클이 시작됐어요. 오늘 기록으로 첫 스탬프를 찍어보세요",
                route = event.route,
            )
        }.onFailure { log.warn("notification.append.fail type=SPIRIT_ARRIVED to={} — {}", event.userId, it.message) }
    }

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onHabitCheer(event: HabitCheerEvent) {
        runCatching {
            notificationService.append(
                userId = event.toUserId,
                type = NotificationType.CHEER,
                title = "💌 ${event.fromNickname} 님이 응원을 보냈어요",
                body = event.message,
                route = event.route,
            )
        }.onFailure { log.warn("notification.append.fail type=CHEER to={} — {}", event.toUserId, it.message) }
    }
}

/**
 * 아프젝 v2: 친구 습관 페어 이벤트 (요청 도착 / 수락 / 거절·취소 / 상대 중단 / 연장 요청) — HabitService 가 도메인 tx 안에서 publish.
 * 푸시(FcmEventListener.onHabitPair)와 알림함 append(본 listener, type HABIT) 가 각자 수신한다.
 */
data class HabitPairEvent(
    val fromUserId: String,
    val toUserId: String,
    val title: String,
    val body: String,
    val route: String = "/record",
)

/**
 * 아프젝 v2: 키우기 완료 사이클이 새 사이클로 리셋될 때 notifyNext 신청 유저에게 1회 발행 (GrowthService — rows==1 인 쪽만).
 * 스케줄러 경로(tx 밖 발행)는 fallbackExecution=true 로 즉시 처리된다.
 */
data class SpiritArrivedEvent(
    val userId: String,
    val speciesNameKo: String,
    val route: String = "/grow",
)

/**
 * 결제 승인 확정 이벤트 — EntitlementService.grant 가 REASON_PURCHASE 로 신규 GRANTED 를
 * 커밋하는 tx 안에서 publish (IAP verify / RTDN webhook 양 경로 공통 choke-point).
 * SYSTEM_GRANT(전원 기본 부여)/ADMIN_OVERRIDE 는 결제가 아니므로 발행하지 않는다.
 */
data class PaymentCompletedEvent(
    val userId: String,
    /** 부여된 entitlement key — 알림 본문에는 노출하지 않고 로깅/후속 확장용. */
    val entitlementKey: String,
)
