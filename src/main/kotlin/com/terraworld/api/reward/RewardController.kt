package com.terraworld.api.reward

import com.terraworld.api.attendance.AttendanceService
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RewardApi
import io.terraworld.api.model.AdRewardResponse
import io.terraworld.api.model.AttendanceCheckInResponse
import io.terraworld.api.model.AttendanceResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * RewardApi (3 endpoints — claimAdReward, checkInAttendance, getAttendanceState) implement.
 * spec 의 tags=[Reward] 그룹핑 — Attendance 도메인이 흡수됨 (ADR-018).
 *
 * ARCH-008-phase-5/6 후: RewardService + AttendanceService 모두 generated DTO
 * 직접 반환. controller 매퍼 0.
 */
@Tag(name = "Reward", description = "광고/출석 보상 API")
@RestController
@RequestMapping("/api/v1")
class RewardController(
    private val rewardService: RewardService,
    private val attendanceService: AttendanceService,
) : RewardApi {
    @Operation(
        summary = "광고 시청 보상 수령",
        description = "하루 최대 5회까지 광고 시청 시 스페셜 코인 1개 지급.",
    )
    override fun claimAdReward(): ResponseEntity<AdRewardResponse> {
        val response = rewardService.claimAdReward(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "출석 체크인", description = "오늘 1회 한정. 보상 + 갱신된 attendance 상태를 반환.")
    override fun checkInAttendance(): ResponseEntity<AttendanceCheckInResponse> {
        val response = attendanceService.checkIn(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "출석 현황 조회", description = "오늘 수령 여부 / streak / 보상 예정 코인 등.")
    override fun getAttendanceState(): ResponseEntity<AttendanceResponse> {
        val response = attendanceService.getState(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }
}
