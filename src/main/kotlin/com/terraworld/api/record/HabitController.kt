package com.terraworld.api.record

import com.terraworld.domain.record.HabitTracker
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.HabitApi
import io.terraworld.api.model.HabitCheckInResponse
import io.terraworld.api.model.HabitCreateRequest
import io.terraworld.api.model.HabitListResponse
import io.terraworld.api.model.HabitTrackerResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * HabitApi 구현 — 습관 트래커(7일 연속 기록 → 반짝이). 낙서장 P1 습관/일상 분리.
 */
@Tag(name = "Habit", description = "습관 트래커 API")
@RestController
@RequestMapping("/api/v1")
class HabitController(
    private val habitService: HabitService,
) : HabitApi {
    @Operation(summary = "습관 트래커 생성")
    override fun createHabit(
        @Valid habitCreateRequest: HabitCreateRequest,
    ): ResponseEntity<HabitTrackerResponse> {
        val tracker =
            habitService.createTracker(
                SecurityUtil.getCurrentUserId(),
                habitCreateRequest.title,
                habitCreateRequest.friendUserId,
            )
        return ResponseEntity.ok(toApi(tracker))
    }

    @Operation(summary = "활성 습관 트래커 목록")
    override fun listHabits(): ResponseEntity<HabitListResponse> {
        val trackers = habitService.listActive(SecurityUtil.getCurrentUserId()).map { toApi(it) }
        return ResponseEntity.ok(HabitListResponse(trackers = trackers))
    }

    @Operation(summary = "습관 체크인", description = "7일 cycle 완료 시 반짝이 지급 (친구 연동 2배)")
    override fun checkInHabit(trackerId: Long): ResponseEntity<HabitCheckInResponse> {
        val result = habitService.checkIn(SecurityUtil.getCurrentUserId(), trackerId)
        return ResponseEntity.ok(
            HabitCheckInResponse(
                tracker = toApi(result.tracker),
                cycleCompleted = result.cycleCompleted,
                sparkleGranted = result.sparkleGranted,
            ),
        )
    }

    private fun toApi(t: HabitTracker): HabitTrackerResponse =
        HabitTrackerResponse(
            id = t.id,
            title = t.title,
            currentStreakDays = t.currentStreakDays,
            cycleLengthDays = t.cycleLengthDays,
            completedCycles = t.completedCycles,
            status = HabitTrackerResponse.Status.forValue(t.status.name),
            lastCheckedDate = t.lastCheckedDate,
            friendLinked = t.friendLinkId != null,
        )
}
