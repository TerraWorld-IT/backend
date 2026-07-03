package com.terraworld.api.reward

import com.terraworld.api.attendance.AttendanceService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.AdRewardResponse
import io.terraworld.api.model.AdRewardResponseReward
import io.terraworld.api.model.AttendanceCheckInResponse
import io.terraworld.api.model.AttendanceCheckInResponseReward
import io.terraworld.api.model.AttendanceResponse
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RewardController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class RewardControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var rewardService: RewardService

    @MockBean private lateinit var attendanceService: AttendanceService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_rewards_ad 200`() {
        // N9 (구현 계획서 v4): claimAdReward 시그니처가 (userId, nonce?, nonceSource) 로 확장됨.
        // controller 는 1-arg 로 호출 (default args), mock 은 any() 로 매칭하여 default arg
        // 까지 cover.
        whenever(rewardService.claimAdReward(eq(TEST_USER_ID), anyOrNull(), any<String>())).thenReturn(
            AdRewardResponse(
                reward = AdRewardResponseReward(specialCoins = 1),
                dailyWatchCount = 1,
                remainingToday = 4,
                updatedCurrency = currency(),
            ),
        )

        // N9 codegen sync: controller 가 consumes = application/json 요구 — body 가 nullable
        // 이라도 Content-Type 헤더 필요. 빈 body "{}" + JSON content type.
        mockMvc
            .perform(post("/api/v1/rewards/ad").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reward.specialCoins").value(1))
            .andExpect(jsonPath("$.dailyWatchCount").value(1))
    }

    @Test
    fun `GET _api_v1_rewards_attendance 200`() {
        whenever(attendanceService.getState(eq(TEST_USER_ID))).thenReturn(
            AttendanceResponse(
                today = false,
                streak = 3,
                longestStreak = 7,
                rewardBasicCoins = 5,
                bonusEligible = false,
            ),
        )

        mockMvc
            .perform(get("/api/v1/rewards/attendance"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.streak").value(3))
            .andExpect(jsonPath("$.rewardBasicCoins").value(5))
    }

    @Test
    fun `POST _api_v1_rewards_attendance 200`() {
        whenever(attendanceService.checkIn(eq(TEST_USER_ID))).thenReturn(
            AttendanceCheckInResponse(
                reward = AttendanceCheckInResponseReward(basicCoins = 5, bonus = false),
                attendance =
                    AttendanceResponse(
                        today = true,
                        streak = 4,
                        longestStreak = 7,
                        rewardBasicCoins = 5,
                        bonusEligible = false,
                    ),
                currency = currency(),
            ),
        )

        mockMvc
            .perform(post("/api/v1/rewards/attendance"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reward.basicCoins").value(5))
            .andExpect(jsonPath("$.attendance.streak").value(4))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST _api_v1_rewards_ad with nonce body — nonce 가 service 에 전달됨`() {
        // Codex round 2 RD2-2 (LOW): body 의 nonce 가 controller → service 로 정확히 전달되는지
        // 회귀 검증. 위 200 case 는 `{}` body 만 보내 nonce 없는 backward-compat 경로만 cover.
        // 본 case 는 specific nonce string 을 eq() 로 verify 하여 mapping 회귀 차단.
        val testNonce = "550e8400-e29b-41d4-a716-446655440000"
        whenever(rewardService.claimAdReward(eq(TEST_USER_ID), eq(testNonce), eq("CLIENT"))).thenReturn(
            AdRewardResponse(
                reward = AdRewardResponseReward(specialCoins = 1),
                dailyWatchCount = 1,
                remainingToday = 4,
                updatedCurrency = currency(),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/rewards/ad")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"nonce\":\"$testNonce\"}"),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.reward.specialCoins").value(1))
    }

    @Test
    fun `POST _api_v1_rewards_ad 429 when daily limit exceeded`() {
        // N9: 시그니처 확장에 따른 any() 매칭 (위 200 case 와 동일).
        whenever(rewardService.claimAdReward(eq(TEST_USER_ID), anyOrNull(), any<String>()))
            .thenThrow(BusinessException(ErrorCode.AD_DAILY_LIMIT_EXCEEDED))

        // N9 codegen sync: controller 가 consumes = application/json 요구 — body 가 nullable
        // 이라도 Content-Type 헤더 필요. 빈 body "{}" + JSON content type.
        mockMvc
            .perform(post("/api/v1/rewards/ad").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("AD_DAILY_LIMIT_EXCEEDED"))
    }

    @Test
    fun `POST _api_v1_rewards_attendance 409 when already checked today`() {
        whenever(attendanceService.checkIn(eq(TEST_USER_ID)))
            .thenThrow(BusinessException(ErrorCode.ATTENDANCE_ALREADY_CHECKED))

        mockMvc
            .perform(post("/api/v1/rewards/attendance"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ATTENDANCE_ALREADY_CHECKED"))
    }

    private fun currency() =
        CurrencyResponse(
            balances =
                listOf(
                    CurrencyBalance(code = "COIN", amount = 100),
                    CurrencyBalance(code = "RUBY", amount = 5),
                    CurrencyBalance(code = "DEW", amount = 0),
                    CurrencyBalance(code = "SUN", amount = 0),
                    CurrencyBalance(code = "BOLT", amount = 0),
                    CurrencyBalance(code = "WIND", amount = 0),
                ),
        )
}
