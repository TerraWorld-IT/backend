package com.terraworld.api.notification

import com.terraworld.domain.userdevice.UserDeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * UltraPlan v3 P-FCM-001 + Codex pre-audit A-FCM-001 (2026-05-18):
 *
 * FCM 발송 트리거 — 도메인 event listener.
 *
 * 본 cycle 작성 = listener skeleton + event class. 실 event publish 는 별 cycle 에서
 * RecordService / AttendanceService / TerrariumService 에 ApplicationEventPublisher 주입 후 publish.
 *
 * 처리 event:
 *  1) [AttendanceMissedEvent]: 3일 미접속 사용자 — 일 1회 batch
 *  2) [WiltingEnteredEvent]: 시들기 stage 진입 (3일/7일/14일) — 즉시
 *  3) [FriendActivityEvent]: 친구 신규 기록 / 랭킹 1등 / invite 수락 — 즉시
 *
 * 다국어 payload (I-T-MIGRATE-001 의존):
 *  - title / body 는 ko (현 시점) — Phase 5+ EN/JA 도입 시 user.locale 따라 분기
 *  - i18n key 는 ko.json 의 `notification.*` namespace
 *
 * 본 listener 는 `@Async` + `@TransactionalEventListener(AFTER_COMMIT, fallbackExecution=true)` —
 * FCM 발송 실패가 DB rollback 유발 X, 그리고 **커밋 확정 후에만 발송**한다. 구 @EventListener 는
 * 발행 즉시 비동기 발송을 시작해 트랜잭션이 이후 롤백되면(예: 습관 체크인 저장 실패) 실제로는
 * 일어나지 않은 활동의 푸시가 나갔다 (2026-07-20 Codex R1). fallbackExecution=true 라
 * 스케줄러 등 트랜잭션 밖 발행은 기존대로 즉시 처리된다.
 *
 * BE-18 (2026-07-15 성능 감사): 토큰별 순차 `sendToToken` 루프(토큰당 HTTP 왕복) →
 * [FcmService.sendToTokens] (`sendEachForMulticast`, 500 토큰/호출) 배치 전송.
 */
@Component
class FcmEventListener(
    private val fcmService: FcmService,
    private val userDeviceRepository: UserDeviceRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onWiltingEntered(event: WiltingEnteredEvent) {
        val tokens = userDeviceRepository.findAllByUserIdAndIsActiveTrue(event.userId).map { it.token }
        if (tokens.isEmpty()) {
            log.info("fcm.wilt.skip user={} (no devices)", event.userId)
            return
        }
        val (title, body) =
            when (event.stage) {
                1 -> "🌱 식물이 시들기 시작했어요" to "오늘 기록을 남기고 식물을 살려주세요"
                2 -> "🥀 식물이 시들었어요" to "광고 시청 또는 기록으로 복구해주세요 (햇살 +1)"
                3 -> "💀 식물이 곧 죽을 위기예요" to "오랜만에 돌아와주세요! 광고 1회로 즉시 복구 가능"
                else -> "TerraWorld" to "식물 상태를 확인해주세요"
            }
        fcmService.sendToTokens(
            tokens,
            title,
            body,
            mapOf("type" to "WILTING", "stage" to event.stage.toString()),
        )
    }

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onAttendanceMissed(event: AttendanceMissedEvent) {
        val tokens = userDeviceRepository.findAllByUserIdAndIsActiveTrue(event.userId).map { it.token }
        if (tokens.isEmpty()) return
        fcmService.sendToTokens(
            tokens,
            "🌿 오늘도 한 줄 기록 어때요?",
            "출석 보상 + 카테고리 토큰을 받을 수 있어요",
            mapOf("type" to "ATTENDANCE", "missedDays" to event.missedDays.toString()),
        )
    }

    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onFriendActivity(event: FriendActivityEvent) {
        val tokens = userDeviceRepository.findAllByUserIdAndIsActiveTrue(event.toUserId).map { it.token }
        if (tokens.isEmpty()) return
        fcmService.sendToTokens(
            tokens,
            "👯 친구가 활동했어요",
            event.message,
            // route: 클라 capacitor.client.ts 의 pushNotificationActionPerformed 가 탭 시 이동.
            mapOf("type" to "FRIEND", "fromUserId" to event.fromUserId, "route" to event.route),
        )
    }

    /** 습관 응원 푸시 (apjek social loop) — [FriendActivityEvent] 패턴 복제. 알림함 append 는 NotificationEventListener 별도 경로. */
    @Async("fcmExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    fun onHabitCheer(event: HabitCheerEvent) {
        val tokens = userDeviceRepository.findAllByUserIdAndIsActiveTrue(event.toUserId).map { it.token }
        if (tokens.isEmpty()) return
        fcmService.sendToTokens(
            tokens,
            "💌 ${event.fromNickname} 님이 응원을 보냈어요",
            event.message,
            mapOf("type" to "CHEER", "fromUserId" to event.fromUserId, "route" to event.route),
        )
    }
}

// 도메인 event 클래스 — 본 cycle 의 spec/skeleton. 별 cycle 에서 publisher 배선.
data class WiltingEnteredEvent(
    val userId: String,
    val stage: Int,
)

data class AttendanceMissedEvent(
    val userId: String,
    val missedDays: Int,
)

data class FriendActivityEvent(
    val fromUserId: String,
    val toUserId: String,
    val message: String,
    /** 푸시 탭 시 이동 경로 — 습관 연동은 /record, 초대 수락은 /friends. */
    val route: String = "/record",
)

/**
 * 습관 응원 이벤트 (apjek social loop) — HabitCheerService.cheer 가 도메인 tx 안에서 publish.
 * 푸시(본 listener)와 알림함 append(NotificationEventListener) 가 각자 수신한다.
 */
data class HabitCheerEvent(
    val fromUserId: String,
    val toUserId: String,
    /** 발신자 닉네임 — 푸시/알림함 title 조립용 (service 가 tx 안에서 해석해 전달). */
    val fromNickname: String,
    /** 응원 메시지 원문 (1..100자 — service 가 검증). */
    val message: String,
    val route: String = "/record",
)
