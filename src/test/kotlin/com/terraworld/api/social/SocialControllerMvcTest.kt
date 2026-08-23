package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.InviteAcceptResponse
import io.terraworld.api.model.InviteAcceptResponseReward
import io.terraworld.api.model.InviteResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.net.URI
import java.time.OffsetDateTime

@WebMvcTest(SocialController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class SocialControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var inviteService: InviteService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_invites 200`() {
        whenever(inviteService.createInvite(eq(TEST_USER_ID))).thenReturn(
            InviteResponse(
                inviteCode = "ABCD1234",
                inviteLink = URI.create("https://terraworld.app/share/ABCD1234"),
                expiresAt = OffsetDateTime.parse("2026-05-14T00:00:00Z"),
                inviterRuby = 30,
                inviteeRuby = 10,
            ),
        )

        mockMvc
            .perform(post("/api/v1/invites"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.inviteCode").value("ABCD1234"))
            .andExpect(jsonPath("$.inviterRuby").value(30))
    }

    @Test
    fun `POST _api_v1_invites_code_accept 200`() {
        whenever(inviteService.acceptInvite(eq(TEST_USER_ID), eq("ABCD1234"))).thenReturn(
            InviteAcceptResponse(
                message = "초대 수락 완료",
                reward = InviteAcceptResponseReward(specialCoins = 10, inviterRuby = 30, inviteeRuby = 10),
            ),
        )

        mockMvc
            .perform(post("/api/v1/invites/ABCD1234/accept"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").value("초대 수락 완료"))
            .andExpect(jsonPath("$.reward.specialCoins").value(10))
            .andExpect(jsonPath("$.reward.inviterRuby").value(30))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST _api_v1_invites_code_accept 404 when invite not found`() {
        whenever(inviteService.acceptInvite(eq(TEST_USER_ID), eq("BADCODE")))
            .thenThrow(BusinessException(ErrorCode.INVITE_NOT_FOUND))

        mockMvc
            .perform(post("/api/v1/invites/BADCODE/accept"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("INVITE_NOT_FOUND"))
    }

    @Test
    fun `POST _api_v1_invites_code_accept 409 when invite already accepted`() {
        whenever(inviteService.acceptInvite(eq(TEST_USER_ID), eq("USEDCODE")))
            .thenThrow(BusinessException(ErrorCode.INVITE_ALREADY_ACCEPTED))

        mockMvc
            .perform(post("/api/v1/invites/USEDCODE/accept"))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("INVITE_ALREADY_ACCEPTED"))
    }

    @Test
    fun `POST _api_v1_invites_code_accept 400 when self accept`() {
        whenever(inviteService.acceptInvite(eq(TEST_USER_ID), eq("SELFCODE")))
            .thenThrow(BusinessException(ErrorCode.INVITE_SELF_ACCEPT))

        mockMvc
            .perform(post("/api/v1/invites/SELFCODE/accept"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVITE_SELF_ACCEPT"))
    }
}
