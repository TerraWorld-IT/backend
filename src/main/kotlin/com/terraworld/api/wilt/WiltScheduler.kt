package com.terraworld.api.wilt

import com.terraworld.api.notification.AttendanceMissedEvent
import com.terraworld.api.notification.WiltingEnteredEvent
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.WiltNotificationMarker
import com.terraworld.domain.terrarium.WiltNotificationMarkerRepository
import com.terraworld.domain.terrarium.WiltScanProjection
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate
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
 *
 * BE-05 (2026-07-15 성능 감사): 쿼리/트랜잭션 배치화.
 *  - 유저별 `findMaxRecordedDate` 루프(N쿼리) → `findMaxRecordedDatePerUser` GROUP BY 1쿼리.
 *  - 유저×마커타입 `existsByUserIdAndMarkerType` 루프 → chunk 유저 마커 `findAllByUserIdIn`
 *    일괄 로드 후 메모리 diff. 마커 저장도 saveAll 배치.
 *  - 단일 장기 @Transactional → 유저 [CHUNK_SIZE] 단위 [TransactionTemplate] 분할 —
 *    한 chunk 실패가 전체 스캔을 롤백하지 않고, 커넥션 장기 점유도 제거.
 *
 * R4-WILT (2026-07-15 적대적 리뷰) 2건:
 *  - 마커 수명주기: 기존 `days <= 0` 단독 삭제는 자정 실행 시 days 가 항상 >= 1 이라 도달
 *    불가 — 마커 영구 잔존으로 다음 비활동 주기의 알림이 전부 억제됐다. 마커는 "현재 진행
 *    중인 비활동 주기" 에만 존재해야 한다: 유저의 최신 마커 생성일이 마지막 활동일
 *    (effective) 이후가 아니면 이전 주기 잔재 → 전체 삭제 후 이번 주기 알림 재개.
 *  - 발행 순서: 이벤트 publish 를 chunk tx **커밋 성공 후** 로 이동. 기존(tx 블록 안
 *    publish)은 @Async listener 가 즉시 발송을 시작해, 커밋 실패 시 "이미 발송 + 마커
 *    미커밋 → 재실행 시 중복 발송" 이었다. 커밋 후 프로세스 크래시 시 발행 유실(알림 1회
 *    누락) 가능성은 중복 발송보다 낮은 리스크로 수용.
 *  - 멱등성: 마커 존재 여부가 발송 gate — chunk 실패 시 마커 미커밋 + 이벤트 미발행이므로
 *    재실행이 안전하게 재처리한다.
 */
@Component
class WiltScheduler(
    private val terrariumRepository: TerrariumRepository,
    private val recordRepository: RecordRepository,
    private val markerRepository: WiltNotificationMarkerRepository,
    private val eventPublisher: ApplicationEventPublisher,
    transactionManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val transactionTemplate = TransactionTemplate(transactionManager)

    companion object {
        private const val WILT_STAGE_1_DAYS = 3L
        private const val WILT_STAGE_2_DAYS = 7L
        private const val WILT_STAGE_3_DAYS = 14L
        private const val ATTENDANCE_NUDGE_DAYS = 1L
        private const val MARKER_ATTENDANCE = "ATTENDANCE_1"

        /** BE-05: 유저 chunk 크기 — chunk 당 짧은 tx 1개 (마커 삭제/로드/저장). */
        private const val CHUNK_SIZE = 500

        private fun wiltMarker(stage: Int) = "WILT_$stage"
    }

    /** BE-05: chunk 내 알림 대상 (경과일 + 도달 최고 단계). */
    private data class WiltPending(
        val userId: String,
        val days: Long,
        val maxStage: Int,
    )

    /**
     * 매일 자정 KST. 미기록 사용자의 시들기 단계 전이 감지 + FCM event publish.
     *
     * Codex audit Q4: 마커 기반 — `days >= threshold && 마커 없음` 일 때만 publish.
     * 자정에 앱이 다운돼 있다가 복구돼도 누락분 일괄 발송 (정각 일치 방식의 누락 제거).
     * 사용자가 다시 기록/복구하면 다음 스캔이 이전 주기 마커(최신 notifiedAt <= 마지막
     * 활동일)를 전체 삭제 → 다음 시들기 사이클에 재알림 (R4-WILT 마커 수명주기).
     */
    @Scheduled(cron = "0 0 0 * * *", zone = "Asia/Seoul")
    fun runDailyWiltScan() {
        val today = KstTime.today()
        var wiltEvents = 0
        var nudgeEvents = 0
        var failedChunks = 0

        // 읽기 2쿼리 (tx 밖 — 각자 repo 의 readOnly tx)
        val terrariums = terrariumRepository.findAllWiltScanProjections()
        val maxRecordedDates =
            recordRepository
                .findMaxRecordedDatePerUser()
                .associate { it.userId to it.maxRecordedDate }

        terrariums.chunked(CHUNK_SIZE).forEach { chunk ->
            try {
                // R4-WILT: tx 안에서는 마커 삭제/저장(claim)만 커밋하고, 발행할 이벤트 목록을
                // 반환받아 **커밋 성공 후** publish. 커밋 실패 chunk 는 이벤트 발행 0 —
                // 재실행 시 마커 미존재로 재처리 (rollback-after-publish 중복 제거).
                val events =
                    transactionTemplate.execute { processChunk(chunk, maxRecordedDates, today) }
                        ?: emptyList()
                events.forEach { eventPublisher.publishEvent(it) }
                wiltEvents += events.count { it is WiltingEnteredEvent }
                nudgeEvents += events.count { it is AttendanceMissedEvent }
            } catch (e: Exception) {
                failedChunks++
                log.error(
                    "wilt.scan.chunk-failed size={} — 다음 chunk 계속 (마커 미커밋 + 이벤트 미발행 → 재실행 시 재처리)",
                    chunk.size,
                    e,
                )
            }
        }

        log.info(
            "wilt.scan.done date={} wiltEvents={} nudgeEvents={} failedChunks={}",
            today,
            wiltEvents,
            nudgeEvents,
            failedChunks,
        )
    }

    /**
     * chunk 1개 처리 (호출자가 tx 로 감쌈). 마커 삭제/저장만 수행하고 이벤트는 발행하지 않는다.
     *
     * @return 커밋 성공 후 호출자가 발행할 이벤트 목록 ([WiltingEnteredEvent] / [AttendanceMissedEvent])
     */
    private fun processChunk(
        chunk: List<WiltScanProjection>,
        maxRecordedDates: Map<String, LocalDate?>,
        today: LocalDate,
    ): List<Any> {
        val clearedUserIds = mutableListOf<String>()
        val pendings = mutableListOf<WiltPending>()

        chunk.forEach { terrarium ->
            val userId = terrarium.userId
            val lastRecordDate = maxRecordedDates[userId]
            val wiltRecoveredDate = terrarium.wiltRecoveredAt?.toLocalDate()
            val effective =
                listOfNotNull(lastRecordDate, wiltRecoveredDate).maxOrNull()
                    ?: return@forEach // 기록·복구 모두 없는 신규 사용자 — skip

            val days = ChronoUnit.DAYS.between(effective, today)

            // 오늘 기록/복구함 — 마커 클리어. 자정 cron 에선 days >= 1 이라 사실상 도달 불가
            // (수동/보정 실행 대비 유지). 실질 클리어는 아래 stale 마커 판정이 담당.
            if (days <= 0L) {
                clearedUserIds += userId
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
            pendings += WiltPending(userId, days, maxStage)
        }

        if (pendings.isEmpty()) {
            if (clearedUserIds.isNotEmpty()) {
                markerRepository.deleteAllByUserIdIn(clearedUserIds)
            }
            return emptyList()
        }

        // 유저×마커타입 exists 루프 → IN 일괄 로드 후 메모리 diff
        val markersByUser: Map<String, List<WiltNotificationMarker>> =
            markerRepository
                .findAllByUserIdIn(pendings.map { it.userId })
                .groupBy { it.userId }

        // R4-WILT (마커 수명주기): 마커는 "현재 진행 중인 비활동 주기" 에만 존재해야 한다.
        // 판정: 유저의 최신 마커 생성일(notifiedAt)이 마지막 활동일(effective = today - days)
        // **이후가 아니면**(<=) 그 마커들은 활동으로 리셋된 이전 주기의 잔재 → 전체 삭제해
        // 이번 주기의 알림(재시들기 등)이 다시 발송되게 한다. 유저의 마커는 스캔마다 stale
        // 선삭제 후 생성되므로 주기 단위로 함께 생성/삭제된다 — 최신 1개 판정으로 충분.
        // (기존 `days <= 0` 단독 삭제는 자정 실행에서 도달 불가 — 마커 영구 잔존 버그.)
        pendings.forEach { pending ->
            val newestMarkerAt = markersByUser[pending.userId]?.maxOfOrNull { it.notifiedAt } ?: return@forEach
            val lastActiveDate = today.minusDays(pending.days)
            if (!newestMarkerAt.toLocalDate().isAfter(lastActiveDate)) {
                clearedUserIds += pending.userId
            }
        }
        if (clearedUserIds.isNotEmpty()) {
            markerRepository.deleteAllByUserIdIn(clearedUserIds)
        }
        val staleClearedUsers = clearedUserIds.toSet()

        val events = mutableListOf<Any>()
        val newMarkers = mutableListOf<WiltNotificationMarker>()

        pendings.forEach { pending ->
            // stale 클리어된 유저는 빈 마커 집합으로 시작 (bulk delete 는 영속성 컨텍스트 밖 —
            // 로드해 둔 markersByUser 를 그대로 쓰면 삭제분이 남아 발송을 잘못 억제한다)
            val existing: Set<String> =
                if (pending.userId in staleClearedUsers) {
                    emptySet()
                } else {
                    markersByUser[pending.userId].orEmpty().map { it.markerType }.toSet()
                }

            // notifiedAt 을 KstTime.now() 로 명시 — 엔티티 기본값 LocalDateTime.now() 는 JVM
            // default TZ(컨테이너 UTC) 라 KST 자정 스캔 마커가 전일 날짜로 기록돼, 위 stale
            // 판정(KST 날짜 비교)이 현재 주기 마커를 이전 주기로 오판(중복 발송)하게 된다.
            for (stage in 1..pending.maxStage) {
                if (wiltMarker(stage) !in existing) {
                    events += WiltingEnteredEvent(userId = pending.userId, stage = stage)
                    newMarkers +=
                        WiltNotificationMarker(userId = pending.userId, markerType = wiltMarker(stage), notifiedAt = KstTime.now())
                }
            }

            // 미기록 (1일차+) — 시들기 전 가벼운 출석 nudge, 비활동 주기당 1회
            if (pending.days >= ATTENDANCE_NUDGE_DAYS && MARKER_ATTENDANCE !in existing) {
                events += AttendanceMissedEvent(userId = pending.userId, missedDays = pending.days.toInt())
                newMarkers +=
                    WiltNotificationMarker(userId = pending.userId, markerType = MARKER_ATTENDANCE, notifiedAt = KstTime.now())
            }
        }

        if (newMarkers.isNotEmpty()) {
            markerRepository.saveAll(newMarkers)
        }
        return events
    }
}
