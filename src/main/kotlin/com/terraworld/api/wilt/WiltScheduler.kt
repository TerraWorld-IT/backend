package com.terraworld.api.wilt

import com.terraworld.api.notification.AttendanceMissedEvent
import com.terraworld.api.notification.WiltingEnteredEvent
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.WiltNotificationMarker
import com.terraworld.domain.terrarium.WiltNotificationMarkerRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

/**
 * N14 (구현 계획서 v4, 2026-05-21): 시들기 cron scheduler.
 *
 * 변경 사유: `TerrariumService.computeWiltingState` 는 lazy 계산만 — 사용자가 화면을 열어야
 * 시들기 상태가 보임. 정기 배치 + FCM 알림 (`WiltingEnteredEvent`) 트리거가 부재했음.
 *
 * 본 scheduler: 매일 자정 KST 에 전체 terrarium 을 스캔해
 *  - 미기록 경과일이 정확히 3 / 7 / 14 일이 되는 시점에 `WiltingEnteredEvent` publish
 *    (단계 전이 시점에만 — 매일 중복 알림 회피)
 *  - 미기록 1일차에 `AttendanceMissedEvent` publish (시들기 전 가벼운 nudge)
 *
 * event 는 `FcmEventListener` 가 `@Async("fcmExecutor")` 로 수신 — cron transaction 과 분리.
 * N8 (FCM event publish 배선) 의 wilt/attendance 경로가 본 scheduler 로 구현됨.
 *
 * 경과일 = `max(마지막 record date, terrarium.wiltRecoveredAt)` 기준 —
 * `TerrariumService.computeWiltingState` 와 동일 규칙.
 */
@Component
class WiltScheduler(
    private val terrariumRepository: TerrariumRepository,
    private val recordRepository: RecordRepository,
    private val markerRepository: WiltNotificationMarkerRepository,
    private val eventPublisher: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val WILT_STAGE_1_DAYS = 3L
        private const val WILT_STAGE_2_DAYS = 7L
        private const val WILT_STAGE_3_DAYS = 14L
        private const val ATTENDANCE_NUDGE_DAYS = 1L
        private const val MARKER_ATTENDANCE = "ATTENDANCE_1"

        private fun wiltMarker(stage: Int) = "WILT_$stage"
    }

    /**
     * 매일 자정 KST. 미기록 사용자의 시들기 단계 전이 감지 + FCM event publish.
     *
     * Codex audit Q4: 마커 기반 — `days >= threshold && 마커 없음` 일 때만 publish.
     * 자정에 앱이 다운돼 있다가 복구돼도 누락분 일괄 발송 (정각 일치 방식의 누락 제거).
     * 사용자가 다시 기록하면 (days == 0) 마커 전체 삭제 → 다음 시들기 사이클에 재알림.
     *
     * writable transaction — 마커 insert/delete 수행.
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    @Transactional
    fun runDailyWiltScan() {
        val today = KstTime.today()
        var wiltEvents = 0
        var nudgeEvents = 0

        terrariumRepository.findAllWiltScanProjections().forEach { terrarium ->
            val userId = terrarium.userId
            val lastRecordDate = recordRepository.findMaxRecordedDate(userId)
            val wiltRecoveredDate = terrarium.wiltRecoveredAt?.toLocalDate()
            val effective =
                listOfNotNull(lastRecordDate, wiltRecoveredDate).maxOrNull()
                    ?: return@forEach // 기록·복구 모두 없는 신규 사용자 — skip

            val days = ChronoUnit.DAYS.between(effective, today)

            // 오늘 기록/복구함 — 마커 클리어 (다음 시들기 사이클에 재알림 가능)
            if (days <= 0L) {
                markerRepository.deleteByUserId(userId)
                return@forEach
            }

            // 도달한 최고 시들기 단계 — 1..stage 의 미발송 마커를 모두 발송 (누락분 일괄 처리)
            val maxStage =
                when {
                    days >= WILT_STAGE_3_DAYS -> 3
                    days >= WILT_STAGE_2_DAYS -> 2
                    days >= WILT_STAGE_1_DAYS -> 1
                    else -> 0
                }
            for (stage in 1..maxStage) {
                if (!markerRepository.existsByUserIdAndMarkerType(userId, wiltMarker(stage))) {
                    eventPublisher.publishEvent(WiltingEnteredEvent(userId = userId, stage = stage))
                    markerRepository.save(WiltNotificationMarker(userId = userId, markerType = wiltMarker(stage)))
                    wiltEvents++
                }
            }

            // 미기록 (1일차+) — 시들기 전 가벼운 출석 nudge, 1회만
            if (days >= ATTENDANCE_NUDGE_DAYS &&
                !markerRepository.existsByUserIdAndMarkerType(userId, MARKER_ATTENDANCE)
            ) {
                eventPublisher.publishEvent(
                    AttendanceMissedEvent(userId = userId, missedDays = days.toInt()),
                )
                markerRepository.save(WiltNotificationMarker(userId = userId, markerType = MARKER_ATTENDANCE))
                nudgeEvents++
            }
        }

        log.info("wilt.scan.done date={} wiltEvents={} nudgeEvents={}", today, wiltEvents, nudgeEvents)
    }
}
