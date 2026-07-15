package com.terraworld.api.wilt

import com.terraworld.api.notification.AttendanceMissedEvent
import com.terraworld.api.notification.WiltingEnteredEvent
import com.terraworld.common.time.KstTime
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.record.UserMaxRecordedDateProjection
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.WiltNotificationMarker
import com.terraworld.domain.terrarium.WiltNotificationMarkerRepository
import com.terraworld.domain.terrarium.WiltScanProjection
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.SimpleTransactionStatus
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * R4-WILT (2026-07-15 적대적 리뷰) 회귀 잠금:
 *  1) 마커 수명주기 — 마커는 "현재 진행 중인 비활동 주기" 에만 존재. 이전 주기 잔재
 *     (최신 notifiedAt <= 마지막 활동일)는 전체 삭제 → 다음 주기 알림 재개.
 *     (구 `days <= 0` 단독 삭제는 자정 실행 시 days >= 1 이라 도달 불가 — 영구 억제 버그.)
 *  2) 발행 순서 — 이벤트 publish 는 chunk tx 커밋 성공 후. 커밋 실패 chunk 는 발행 0.
 */
class WiltSchedulerTest {
    private val terrariumRepository: TerrariumRepository = mock()
    private val recordRepository: RecordRepository = mock()
    private val markerRepository: WiltNotificationMarkerRepository = mock()
    private val eventPublisher: ApplicationEventPublisher = mock()
    private val transactionManager: PlatformTransactionManager = mock()

    private val today: LocalDate = KstTime.today()

    private fun scheduler() =
        WiltScheduler(
            terrariumRepository,
            recordRepository,
            markerRepository,
            eventPublisher,
            transactionManager,
        )

    @BeforeEach
    fun setup() {
        whenever(transactionManager.getTransaction(anyOrNull())).thenReturn(SimpleTransactionStatus())
    }

    /** user 1명 + 마지막 기록일(today - N일) + 기존 마커 세팅. */
    private fun seed(
        userId: String,
        lastRecordDaysAgo: Long,
        markers: List<WiltNotificationMarker> = emptyList(),
    ) {
        whenever(terrariumRepository.findAllWiltScanProjections()).thenReturn(
            listOf(
                object : WiltScanProjection {
                    override val userId: String = userId
                    override val wiltRecoveredAt: LocalDateTime? = null
                },
            ),
        )
        whenever(recordRepository.findMaxRecordedDatePerUser()).thenReturn(
            listOf(
                object : UserMaxRecordedDateProjection {
                    override val userId: String = userId
                    override val maxRecordedDate: LocalDate? = today.minusDays(lastRecordDaysAgo)
                },
            ),
        )
        whenever(markerRepository.findAllByUserIdIn(any())).thenReturn(markers)
    }

    private fun marker(
        userId: String,
        type: String,
        notifiedDaysAgo: Long,
    ) = WiltNotificationMarker(
        userId = userId,
        markerType = type,
        notifiedAt = today.minusDays(notifiedDaysAgo).atStartOfDay(),
    )

    @Test
    fun `3일 미기록 신규 주기 — WILT_1 + ATTENDANCE 발행 + 마커 저장`() {
        seed("user-1", lastRecordDaysAgo = 3)

        scheduler().runDailyWiltScan()

        val eventCaptor = argumentCaptor<Any>()
        verify(eventPublisher, times(2)).publishEvent(eventCaptor.capture())
        assertThat(eventCaptor.allValues.filterIsInstance<WiltingEnteredEvent>())
            .containsExactly(WiltingEnteredEvent(userId = "user-1", stage = 1))
        assertThat(eventCaptor.allValues.filterIsInstance<AttendanceMissedEvent>())
            .containsExactly(AttendanceMissedEvent(userId = "user-1", missedDays = 3))

        val markerCaptor = argumentCaptor<List<WiltNotificationMarker>>()
        verify(markerRepository).saveAll(markerCaptor.capture())
        assertThat(markerCaptor.firstValue.map { it.markerType })
            .containsExactlyInAnyOrder("WILT_1", "ATTENDANCE_1")
    }

    @Test
    fun `현재 주기 마커(활동일 이후 생성)는 유지 — 재발행 억제 + 삭제 없음`() {
        // 마지막 활동 3일 전, 마커는 활동 이후(1~2일 전) 생성 = 현재 주기 소속 → 멱등 유지.
        seed(
            "user-1",
            lastRecordDaysAgo = 3,
            markers =
                listOf(
                    marker("user-1", "WILT_1", notifiedDaysAgo = 1),
                    marker("user-1", "ATTENDANCE_1", notifiedDaysAgo = 2),
                ),
        )

        scheduler().runDailyWiltScan()

        verify(eventPublisher, never()).publishEvent(any<Any>())
        verify(markerRepository, never()).deleteAllByUserIdIn(any())
        verify(markerRepository, never()).saveAll(any<List<WiltNotificationMarker>>())
    }

    @Test
    fun `이전 주기 마커(활동일 이전 생성)는 전체 삭제 — 다음 주기 알림 재개`() {
        // 시들기 주기(WILT 1~3 + nudge 발송)를 겪은 뒤 어제 기록(주기 리셋). 구 코드는
        // days >= 1 이라 마커가 영구 잔존 → 이번 주기의 nudge/시들기 알림이 전부 억제됐다.
        // stale 판정(최신 notifiedAt <= 마지막 활동일)이 전체 삭제해 재알림을 허용해야 한다.
        seed(
            "user-1",
            lastRecordDaysAgo = 1,
            markers =
                listOf(
                    marker("user-1", "WILT_1", notifiedDaysAgo = 12),
                    marker("user-1", "WILT_2", notifiedDaysAgo = 8),
                    marker("user-1", "WILT_3", notifiedDaysAgo = 5),
                    marker("user-1", "ATTENDANCE_1", notifiedDaysAgo = 14),
                ),
        )

        scheduler().runDailyWiltScan()

        verify(markerRepository).deleteAllByUserIdIn(eq(listOf("user-1")))
        // days=1 → maxStage 0 (wilt 이벤트 없음) + nudge 재발행
        val eventCaptor = argumentCaptor<Any>()
        verify(eventPublisher).publishEvent(eventCaptor.capture())
        assertThat(eventCaptor.firstValue).isEqualTo(AttendanceMissedEvent(userId = "user-1", missedDays = 1))
    }

    @Test
    fun `chunk 커밋 실패 시 이벤트 발행 0 — publish 는 커밋 성공 후에만`() {
        // 구 코드는 tx 블록 안에서 publish → @Async listener 가 즉시 발송을 시작해
        // 커밋 실패 시 "이미 발송 + 마커 미커밋 → 재실행 중복" 이었다.
        seed("user-1", lastRecordDaysAgo = 3)
        doThrow(RuntimeException("commit fail")).whenever(transactionManager).commit(anyOrNull())

        // 내부 catch (failedChunks 집계) — 밖으로 throw 되면 안 됨
        scheduler().runDailyWiltScan()

        verify(eventPublisher, never()).publishEvent(any<Any>())
    }
}
