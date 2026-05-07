package com.terraworld.api.social

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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.time.OffsetDateTime

@WebMvcTest(SocialController::class)
@AutoConfigureMockMvc(addFilters = false)
class SocialControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var inviteService: InviteService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_invites 200`() {
        whenever(inviteService.createInvite(eq(TEST_USER_ID))).thenReturn(
            InviteCreateResponse(
                inviteCode = "ABCD1234",
                inviteLink = URI.create("https://terraworld.app/share/ABCD1234"),
                expiresAt = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
            ),
        )

        mockMvc
            .perform(post("/api/v1/invites"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inviteCode").value("ABCD1234"))
    }

    @Test
    fun `POST _api_v1_invites_code_accept 200`() {
        whenever(inviteService.acceptInvite(eq(TEST_USER_ID), eq("ABCD1234"))).thenReturn(
            InviteAcceptResponsePayload(
                message = "초대 수락 완료",
                reward = InviteAcceptReward(specialCoins = 5),
            ),
        )

        mockMvc
            .perform(post("/api/v1/invites/ABCD1234/accept"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("초대 수락 완료"))
            .andExpect(jsonPath("$.reward.specialCoins").value(5))
    }
}
