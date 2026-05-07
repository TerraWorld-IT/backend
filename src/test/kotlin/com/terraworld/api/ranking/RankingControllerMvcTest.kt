package com.terraworld.api.ranking

import com.terraworld.api.ranking.dto.RankingEntry
import com.terraworld.api.ranking.dto.RankingResponse
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(RankingController::class)
@AutoConfigureMockMvc(addFilters = false)
class RankingControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var rankingService: RankingService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_rankings_monthly 200`() {
        whenever(
            rankingService.getMonthly(
                currentUserId = eq(TEST_USER_ID),
                type = eq("engagement"),
                yearMonth = eq(null),
                limit = eq(50),
            ),
        ).thenReturn(
            RankingResponse(
                type = "engagement",
                yearMonth = "2026-04",
                entries =
                    listOf(
                        RankingEntry(rank = 1, userId = "u1", nickname = "1등", score = 42, isSelf = false),
                    ),
                myRank = 5,
                myScore = 12,
            ),
        )

        mockMvc
            .perform(get("/api/v1/rankings/monthly").param("type", "engagement"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.type").value("engagement"))
            .andExpect(jsonPath("$.entries[0].rank").value(1))
            .andExpect(jsonPath("$.myRank").value(5))
    }
}
