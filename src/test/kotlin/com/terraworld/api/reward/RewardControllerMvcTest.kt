package com.terraworld.api.reward

import com.terraworld.api.attendance.AttendanceCheckInResponse
import com.terraworld.api.attendance.AttendanceRewardInfo
import com.terraworld.api.attendance.AttendanceService
import com.terraworld.api.attendance.AttendanceStateResponse
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RewardController::class)
@AutoConfigureMockMvc(addFilters = false)
class RewardControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var rewardService: RewardService

    @MockBean private lateinit var attendanceService: AttendanceService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_rewards_ad 200`() {
        whenever(rewardService.claimAdReward(eq(TEST_USER_ID))).thenReturn(
            AdRewardResponsePayload(
                reward = AdRewardPayload(specialCoins = 1),
                dailyWatchCount = 1,
                remainingToday = 4,
                updatedCurrency = currency(),
            ),
        )

        mockMvc
            .perform(post("/api/v1/rewards/ad"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reward.specialCoins").value(1))
            .andExpect(jsonPath("$.dailyWatchCount").value(1))
    }

    @Test
    fun `GET _api_v1_rewards_attendance 200`() {
        whenever(attendanceService.getState(eq(TEST_USER_ID))).thenReturn(
            AttendanceStateResponse(
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
                reward = AttendanceRewardInfo(basicCoins = 5, bonus = false),
                attendance =
                    AttendanceStateResponse(
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

    private fun currency() =
        CurrencyResponse(
            basicCoins = 100.0,
            specialCoins = 5.0,
            walkTokens = 0.0,
            readTokens = 0.0,
            runTokens = 0.0,
            drawTokens = 0.0,
        )
}
