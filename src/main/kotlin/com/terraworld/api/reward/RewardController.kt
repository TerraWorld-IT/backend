package com.terraworld.api.reward

import com.terraworld.api.attendance.AttendanceService
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RewardApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.attendance.AttendanceCheckInResponse as LocalAttendanceCheckInResponse
import com.terraworld.api.attendance.AttendanceStateResponse as LocalAttendanceStateResponse
import io.terraworld.api.model.AdRewardResponse as ApiAdRewardResponse
import io.terraworld.api.model.AdRewardResponseReward as ApiAdRewardResponseReward
import io.terraworld.api.model.AttendanceCheckInResponse as ApiAttendanceCheckInResponse
import io.terraworld.api.model.AttendanceCheckInResponseReward as ApiAttendanceCheckInResponseReward
import io.terraworld.api.model.AttendanceResponse as ApiAttendanceResponse

/**
 * 두 번째 generated `*Api` 인터페이스 채택 슬라이스 (Reward 도메인 통합).
 *
 * - RewardApi 가 spec 의 tags=[Reward] 기반으로 3개 endpoint 를 묶음:
 *   `claimAdReward` (POST /rewards/ad), `checkInAttendance`
 *   (POST /rewards/attendance), `getAttendanceState` (GET /rewards/attendance).
 * - 본 클래스 단일 controller 가 RewardApi 를 implement → spec 변경 시
 *   컴파일 fail 로 드리프트 가드.
 * - 기존 AttendanceController 는 제거되고 HTTP 진입점이 본 클래스로
 *   통합된다. AttendanceService 와 local DTOs 는 보존 (후속 슬라이스에서
 *   generated DTO 로 마이그레이션 후 제거 예정).
 * - generated DTO ↔ local DTO 인라인 mapper. UserController 와 일부 공통
 *   타입 (CurrencyResponse / CategoryTokenAmount) mapper 가 중복되지만,
 *   private 스코프이므로 컴파일 충돌 없음. 후속 슬라이스에서 공용 mapper
 *   객체로 추출 예정.
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
    override fun claimAdReward(): ResponseEntity<ApiAdRewardResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val local = rewardService.claimAdReward(userId)
        return ResponseEntity.ok(
            ApiAdRewardResponse(
                reward = ApiAdRewardResponseReward(specialCoins = local.reward.specialCoins),
                dailyWatchCount = local.dailyWatchCount,
                remainingToday = local.remainingToday,
                updatedCurrency = local.updatedCurrency,
            ),
        )
    }

    @Operation(summary = "출석 체크인", description = "오늘 1회 한정. 보상 + 갱신된 attendance 상태를 반환.")
    override fun checkInAttendance(): ResponseEntity<ApiAttendanceCheckInResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val local = attendanceService.checkIn(userId)
        return ResponseEntity.ok(local.toApi())
    }

    @Operation(summary = "출석 현황 조회", description = "오늘 수령 여부 / streak / 보상 예정 코인 등.")
    override fun getAttendanceState(): ResponseEntity<ApiAttendanceResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val local = attendanceService.getState(userId)
        return ResponseEntity.ok(local.toApi())
    }

    private fun LocalAttendanceCheckInResponse.toApi(): ApiAttendanceCheckInResponse =
        ApiAttendanceCheckInResponse(
            reward =
                ApiAttendanceCheckInResponseReward(
                    basicCoins = reward.basicCoins,
                    bonus = reward.bonus,
                ),
            attendance = attendance.toApi(),
            currency = currency,
        )

    private fun LocalAttendanceStateResponse.toApi(): ApiAttendanceResponse =
        ApiAttendanceResponse(
            today = today,
            streak = streak,
            longestStreak = longestStreak,
            rewardBasicCoins = rewardBasicCoins,
            bonusEligible = bonusEligible,
        )
}
