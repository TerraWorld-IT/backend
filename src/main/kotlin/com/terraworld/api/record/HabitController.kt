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
import io.terraworld.api.model.MessageResponse
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
        val userId = SecurityUtil.getCurrentUserId()
        val tracker =
            habitService.createTracker(
                userId,
                habitCreateRequest.title,
                habitCreateRequest.friendUserId,
            )
        return ResponseEntity.ok(toApi(tracker, habitService.resolveFriendMeta(userId, listOf(tracker))[tracker.id]))
    }

    @Operation(summary = "활성 습관 트래커 목록")
    override fun listHabits(): ResponseEntity<HabitListResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val trackers = habitService.listActive(userId)
        // 친구 메타(상대 userId/닉네임/참여 여부)는 배치 해석 — 트래커 수만큼 invite/user 조회 N+1 방지.
        val meta = habitService.resolveFriendMeta(userId, trackers)
        return ResponseEntity.ok(HabitListResponse(trackers = trackers.map { toApi(it, meta[it.id]) }))
    }

    @Operation(summary = "습관 체크인", description = "7일 cycle 완료 시 반짝이 지급 (친구 연동 2배)")
    override fun checkInHabit(trackerId: Long): ResponseEntity<HabitCheckInResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val result = habitService.checkIn(userId, trackerId)
        return ResponseEntity.ok(
            HabitCheckInResponse(
                tracker = toApi(result.tracker, habitService.resolveFriendMeta(userId, listOf(result.tracker))[result.tracker.id]),
                cycleCompleted = result.cycleCompleted,
                sparkleGranted = result.sparkleGranted,
            ),
        )
    }

    @Operation(summary = "습관 트래커 중단", description = "BROKEN 전환 — 같은 친구와 새 공동 습관 생성이 가능해진다. 멱등.")
    override fun stopHabit(trackerId: Long): ResponseEntity<MessageResponse> {
        habitService.stop(SecurityUtil.getCurrentUserId(), trackerId)
        return ResponseEntity.ok(MessageResponse(message = "습관을 중단했습니다"))
    }

    private fun toApi(
        t: HabitTracker,
        meta: HabitFriendMeta? = null,
    ): HabitTrackerResponse =
        HabitTrackerResponse(
            id = t.id,
            title = t.title,
            currentStreakDays = t.currentStreakDays,
            cycleLengthDays = t.cycleLengthDays,
            completedCycles = t.completedCycles,
            status = HabitTrackerResponse.Status.forValue(t.status.name),
            lastCheckedDate = t.lastCheckedDate,
            friendLinked = t.friendLinkId != null,
            friendUserId = meta?.friendUserId,
            friendNickname = meta?.friendNickname,
            partnerActive = if (t.friendLinkId != null) meta?.partnerActive ?: false else null,
        )
}
