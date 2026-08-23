package com.terraworld.api.record

import com.terraworld.api.currency.CurrencyService
import com.terraworld.domain.record.HabitTracker
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.HabitApi
import io.terraworld.api.model.HabitCheckInResponse
import io.terraworld.api.model.HabitCheerRequest
import io.terraworld.api.model.HabitCheerResponse
import io.terraworld.api.model.HabitCreateRequest
import io.terraworld.api.model.HabitCycleRewardResponse
import io.terraworld.api.model.HabitListResponse
import io.terraworld.api.model.HabitTrackerResponse
import io.terraworld.api.model.MessageResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * HabitApi 구현 — 습관 트래커(7일 연속 기록 → 반짝이). 아프젝 v2: 친구 요청 수락/거절, 완주 보상(complete/extend).
 */
@Tag(name = "Habit", description = "습관 트래커 API")
@RestController
@RequestMapping("/api/v1")
class HabitController(
    private val habitService: HabitService,
    private val habitCheerService: HabitCheerService,
    private val currencyService: CurrencyService,
) : HabitApi {
    @Operation(summary = "습관 트래커 생성", description = "활성 습관 1개 제한(409 HABIT_LIMIT_EXCEEDED). friendUserId 지정 시 상대 수락 대기(PENDING)")
    override fun createHabit(
        @Valid habitCreateRequest: HabitCreateRequest,
    ): ResponseEntity<HabitTrackerResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val tracker =
            habitService.createTracker(
                userId,
                habitCreateRequest.title,
                habitCreateRequest.friendUserId,
            )
        return ResponseEntity.ok(toApi(tracker, habitService.resolveView(userId, listOf(tracker))[tracker.id]))
    }

    @Operation(summary = "열린 습관 트래커 목록", description = "PENDING/ACTIVE/COMPLETED_UNCLAIMED — COMPLETED/BROKEN 제외")
    override fun listHabits(): ResponseEntity<HabitListResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val trackers = habitService.listOpen(userId)
        // 페어 메타(상대 userId/닉네임/요청 상태)는 배치 해석 — 트래커 수만큼 조회 N+1 방지.
        val views = habitService.resolveView(userId, trackers)
        return ResponseEntity.ok(HabitListResponse(trackers = trackers.map { toApi(it, views[it.id]) }))
    }

    @Operation(summary = "습관 체크인", description = "7일째 체크인 시 COMPLETED_UNCLAIMED (보상은 complete/extend 에서 지급)")
    override fun checkInHabit(trackerId: Long): ResponseEntity<HabitCheckInResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val result = habitService.checkIn(userId, trackerId)
        return ResponseEntity.ok(
            HabitCheckInResponse(
                tracker = toApi(result.tracker, habitService.resolveView(userId, listOf(result.tracker))[result.tracker.id]),
                cycleCompleted = result.cycleCompleted,
                // 아프젝 v2: 체크인의 자동 지급 제거 — 하위호환으로 필드 유지(항상 0)
                sparkleGranted = 0,
            ),
        )
    }

    @Operation(summary = "습관 응원 보내기", description = "친구 연동 습관에 하루 3회/트래커까지 응원 메시지 발송 (푸시 + 알림함)")
    override fun cheerHabit(
        trackerId: Long,
        @Valid habitCheerRequest: HabitCheerRequest,
    ): ResponseEntity<HabitCheerResponse> {
        val saved = habitCheerService.cheer(SecurityUtil.getCurrentUserId(), trackerId, habitCheerRequest.message)
        // 생성 시각 → sentAt (generated 모델의 OffsetDateTime — RecordService 의 UTC 변환 관례).
        return ResponseEntity.ok(HabitCheerResponse(sentAt = saved.createdAt.atOffset(ZoneOffset.UTC)))
    }

    @Operation(summary = "습관 트래커 종료", description = "PENDING 요청 취소 / ACTIVE 중단(본인만 BROKEN, 상대는 PARTNER_STOPPED). 멱등.")
    override fun stopHabit(trackerId: Long): ResponseEntity<MessageResponse> {
        habitService.stop(SecurityUtil.getCurrentUserId(), trackerId)
        return ResponseEntity.ok(MessageResponse(message = "습관을 중단했습니다"))
    }

    @Operation(summary = "친구 습관 요청 수락", description = "수신자 자신의 미러 트래커 id — 시작/연장 요청 수락 (양측 ACTIVE)")
    override fun acceptHabit(trackerId: Long): ResponseEntity<HabitTrackerResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val tracker = habitService.accept(userId, trackerId)
        return ResponseEntity.ok(toApi(tracker, habitService.resolveView(userId, listOf(tracker))[tracker.id]))
    }

    @Operation(summary = "친구 습관 요청 거절", description = "수신자 자신의 미러 트래커 id — 시작 요청은 양측 BROKEN, 연장 요청은 요청자만 BROKEN")
    override fun declineHabit(trackerId: Long): ResponseEntity<MessageResponse> {
        habitService.decline(SecurityUtil.getCurrentUserId(), trackerId)
        return ResponseEntity.ok(MessageResponse(message = "습관 요청을 거절했습니다"))
    }

    @Operation(summary = "완주 보상 수령 + 종료", description = "COMPLETED_UNCLAIMED → COMPLETED. 이미 지급이면 alreadyClaimed=true 멱등 재생")
    override fun completeHabit(trackerId: Long): ResponseEntity<HabitCycleRewardResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val result = habitService.complete(userId, trackerId)
        return ResponseEntity.ok(toRewardApi(userId, result))
    }

    @Operation(summary = "완주 보상 수령 + 연장", description = "보상 지급 후 새 7일 사이클 — solo 즉시 ACTIVE / friend 는 상대 수락 대기(PENDING)")
    override fun extendHabit(trackerId: Long): ResponseEntity<HabitCycleRewardResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val result = habitService.extend(userId, trackerId)
        return ResponseEntity.ok(toRewardApi(userId, result))
    }

    private fun toRewardApi(
        userId: String,
        result: HabitRewardResult,
    ): HabitCycleRewardResponse =
        HabitCycleRewardResponse(
            tracker = toApi(result.tracker, habitService.resolveView(userId, listOf(result.tracker))[result.tracker.id]),
            sparkleGranted = result.sparkleGranted,
            alreadyClaimed = result.alreadyClaimed,
            updatedCurrency = currencyService.currencyResponse(userId),
        )

    private fun toApi(
        t: HabitTracker,
        view: HabitView? = null,
    ): HabitTrackerResponse {
        val partnerStatus = view?.partnerStatus ?: HabitTrackerResponse.PartnerStatus.NONE
        val linked = t.partnerTrackerId != null
        return HabitTrackerResponse(
            id = t.id,
            title = t.title,
            currentStreakDays = t.currentStreakDays,
            cycleLengthDays = t.cycleLengthDays,
            completedCycles = t.completedCycles,
            status = HabitTrackerResponse.Status.forValue(t.status.name),
            partnerStatus = partnerStatus,
            // 상대의 오늘(KST) 체크인 여부 — 페어가 없거나(solo) 상대가 중단했으면 false.
            partnerCheckedToday = view?.partnerCheckedToday ?: false,
            extendStatus = view?.extendStatus ?: HabitTrackerResponse.ExtendStatus.NONE,
            rewardSparkle = view?.rewardSparkle ?: 0,
            lastCheckedDate = t.lastCheckedDate,
            friendLinked = linked,
            friendUserId = view?.friendUserId,
            friendNickname = view?.friendNickname,
            // 하위호환: partnerStatus == ACCEPTED 와 동치. 미연동이면 null.
            partnerActive = if (linked) partnerStatus == HabitTrackerResponse.PartnerStatus.ACCEPTED else null,
            cycleCompletedAt = t.cycleCompletedAt?.atOffset(ZoneOffset.UTC),
        )
    }
}
