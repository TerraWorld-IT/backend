package com.terraworld.api.reward

import com.terraworld.api.attendance.AttendanceService
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RewardApi
import io.terraworld.api.model.AdRewardNonceResponse
import io.terraworld.api.model.AdRewardRequest
import io.terraworld.api.model.AdRewardResponse
import io.terraworld.api.model.AttendanceCheckInResponse
import io.terraworld.api.model.AttendanceResponse
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * RewardApi (4 endpoints — issueAdRewardNonce, claimAdReward, checkInAttendance, getAttendanceState) implement.
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
    private val adRewardNonceService: AdRewardNonceService,
) : RewardApi {
    @Operation(
        summary = "광고 보상 nonce 발급 또는 상태 조회",
        description = "사용자·용도별 활성 서버 nonce 를 반환하며 응답은 캐시하지 않습니다.",
    )
    // 경로·쿼리 파라미터(purpose, 기본 AD_REWARD)는 생성 인터페이스 RewardApi 가 선언한다.
    override fun issueAdRewardNonce(purpose: String): ResponseEntity<AdRewardNonceResponse> {
        val issued = adRewardNonceService.issue(SecurityUtil.getCurrentUserId(), purpose)
        return ResponseEntity
            .ok()
            .cacheControl(CacheControl.noStore())
            .body(
                AdRewardNonceResponse(
                    nonce = issued.nonce,
                    purpose = AdRewardNonceResponse.Purpose.forValue(issued.purpose),
                    status = AdRewardNonceResponse.Status.forValue(issued.status),
                    expiresAt = issued.expiresAt.atOffset(ZoneOffset.UTC),
                ),
            )
    }

    @Operation(
        summary = "광고 시청 보상 수령",
        description = "하루 최대 5회까지 광고 시청 시 스페셜 코인 1개 지급.",
    )
    override fun claimAdReward(adRewardRequest: AdRewardRequest?): ResponseEntity<AdRewardResponse> {
        // N9 (구현 계획서 v4, 2026-05-26): generated interface 가 nullable body 를 받음.
        // body.nonce 가 있으면 service 에 전달 — `ad_reward_nonce_inbox` dedup 활성.
        // body=null 또는 nonce=null 은 backward-compat (legacy warn + audit legacy=true).
        val response =
            rewardService.claimAdReward(
                userId = SecurityUtil.getCurrentUserId(),
                nonce = adRewardRequest?.nonce,
                nonceSource = AdRewardNonceInbox.SOURCE_CLIENT,
            )
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
