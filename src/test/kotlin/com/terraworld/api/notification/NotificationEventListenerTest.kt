package com.terraworld.api.notification

import com.terraworld.domain.notification.NotificationType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * NotificationEventListener 커버 — 도메인 이벤트 → 알림함 append 매핑.
 * (AFTER_COMMIT/@Async 배선은 Spring 통합 영역 — 여기서는 매핑 계약만 검증.)
 */
class NotificationEventListenerTest {
    private lateinit var notificationService: NotificationService
    private lateinit var listener: NotificationEventListener

    @BeforeEach
    fun setup() {
        notificationService = mock(NotificationService::class.java)
        listener = NotificationEventListener(notificationService)
    }

    @Test
    fun `FriendActivityEvent — 수신자에게 FRIEND_JOINED + 푸시와 동일 카피`() {
        listener.onFriendActivity(
            FriendActivityEvent(fromUserId = "a", toUserId = "b", message = "친구가 초대를 수락했어요", route = "/friends"),
        )
        verify(notificationService).append(
            userId = eq("b"),
            type = eq(NotificationType.FRIEND_JOINED),
            title = eq("친구가 활동했어요"),
            body = eq("친구가 초대를 수락했어요"),
            route = eq("/friends"),
        )
    }

    @Test
    fun `PaymentCompletedEvent — 구매자에게 PAYMENT`() {
        listener.onPaymentCompleted(PaymentCompletedEvent(userId = "buyer", entitlementKey = "free_placement"))
        verify(notificationService).append(
            userId = eq("buyer"),
            type = eq(NotificationType.PAYMENT),
            title = eq("💳 결제 완료"),
            body = eq("구매하신 상품이 정상 적용되었어요"),
            route = eq(null),
        )
    }

    @Test
    fun `AttendanceCycleCompletedEvent — 7일차 완주 사용자에게 SYSTEM 보너스 알림`() {
        listener.onAttendanceCycleCompleted(AttendanceCycleCompletedEvent(userId = "user-1"))
        verify(notificationService).append(
            userId = eq("user-1"),
            type = eq(NotificationType.SYSTEM),
            title = eq("7일 출석 완주!"),
            body = eq("보너스 루비를 받았어요"),
            route = eq(null),
        )
    }

    @Test
    fun `HabitCheerEvent — 수신자에게 CHEER + 발신 닉네임 title`() {
        listener.onHabitCheer(
            HabitCheerEvent(fromUserId = "a", toUserId = "b", fromNickname = "태겸", message = "화이팅!"),
        )
        verify(notificationService).append(
            userId = eq("b"),
            type = eq(NotificationType.CHEER),
            title = eq("💌 태겸 님이 응원을 보냈어요"),
            body = eq("화이팅!"),
            route = eq("/record"),
        )
    }

    @Test
    fun `append 실패 — 예외를 삼켜 다른 알림 처리에 전파하지 않음`() {
        whenever(
            notificationService.append(
                userId = eq("b"),
                type = eq(NotificationType.FRIEND_JOINED),
                title = eq("친구가 활동했어요"),
                body = eq("msg"),
                route = eq("/record"),
            ),
        ).thenThrow(RuntimeException("DB down"))
        // 예외 미전파 — @Async 스레드에서 조용히 죽지 않고 로그만 남긴다
        listener.onFriendActivity(FriendActivityEvent(fromUserId = "a", toUserId = "b", message = "msg"))
    }
}
