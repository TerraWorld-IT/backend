package com.terraworld.api.notification

import com.terraworld.domain.userdevice.DevicePlatform
import com.terraworld.domain.userdevice.UserDevice
import com.terraworld.domain.userdevice.UserDeviceRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever

class FcmEventListenerTest {
    private val fcmService = mock<FcmService>()
    private val devices = mock<UserDeviceRepository>()
    private val listener = FcmEventListener(fcmService, devices)

    @BeforeEach
    fun setup() {
        whenever(devices.findAllByUserIdAndIsActiveTrue("b"))
            .thenReturn(listOf(UserDevice(userId = "b", token = "token", platform = DevicePlatform.ANDROID)))
    }

    @Test
    fun `초대 수락은 피그마 가입 문구와 친구 이동 경로로 발송`() {
        listener.onFriendActivity(FriendActivityEvent("a", "b", "친구 님이 초대를 수락했어요", "/friends"))
        verify(fcmService).sendToTokens(
            listOf("token"),
            "나의 초대코드로 친구가 가입했어요",
            "친구의 테라에 놀러 갈 수 있어요",
            mapOf("type" to "FRIEND", "fromUserId" to "a", "route" to "/friends"),
        )
    }

    @Test
    fun `습관과 다른 친구 활동은 기존 문구 유지`() {
        listOf("/record", "/friends").forEach { route ->
            listener.onFriendActivity(FriendActivityEvent("a", "b", "친구가 기록을 남겼어요", route))
            verify(fcmService).sendToTokens(
                listOf("token"),
                "친구가 활동했어요",
                "친구가 기록을 남겼어요",
                mapOf("type" to "FRIEND", "fromUserId" to "a", "route" to route),
            )
        }
    }

    @Test
    fun `시들음 알림은 기록 화면 경로를 포함`() {
        listener.onWiltingEntered(WiltingEnteredEvent("b", 2))
        verify(fcmService).sendToTokens(
            listOf("token"),
            "🥀 식물이 시들었어요",
            "광고 시청 또는 기록으로 복구해주세요 (햇살 +1)",
            mapOf("type" to "WILTING", "stage" to "2", "route" to "/record"),
        )
    }

    @Test
    fun `출석 알림은 기록 화면 경로를 포함`() {
        listener.onAttendanceMissed(AttendanceMissedEvent("b", 3))
        verify(fcmService).sendToTokens(
            listOf("token"),
            "🌿 오늘도 한 줄 기록 어때요?",
            "출석 보상 + 카테고리 토큰을 받을 수 있어요",
            mapOf("type" to "ATTENDANCE", "missedDays" to "3", "route" to "/record"),
        )
    }

    @Test
    fun `정령 도착은 인앱과 같은 피그마 문구로 발송`() {
        listener.onSpiritArrived(SpiritArrivedEvent("b", "고양이"))
        verify(fcmService).sendToTokens(
            listOf("token"),
            "새로운 수수께끼 정령이 찾아왔어요",
            "오늘 부터 다시 정령을 키울 수 있어요.",
            mapOf("type" to "SPIRIT_ARRIVED", "route" to "/grow"),
        )
    }

    @Test
    fun `기기가 없는 사용자는 정령 도착 푸시를 보내지 않는다`() {
        listener.onSpiritArrived(SpiritArrivedEvent("no-device", "고양이"))
        verifyNoInteractions(fcmService)
    }
}
