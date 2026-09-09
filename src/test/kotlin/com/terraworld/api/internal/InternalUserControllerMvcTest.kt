package com.terraworld.api.internal

import com.terraworld.api.user.UserDeletionService
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.UserBootstrapService
import com.terraworld.security.ratelimit.RateLimitFilter
import org.junit.jupiter.api.Test
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(InternalUserController::class, properties = ["auth.internal.token=internal-test-token"])
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class InternalUserControllerMvcTest {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var userBootstrapService: UserBootstrapService

    @MockBean private lateinit var userDeletionService: UserDeletionService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `내부 삭제는 첫 호출과 재시도 모두 204`() {
        repeat(2) {
            mockMvc
                .perform(delete("/api/v1/internal/users/deleted-user").header("X-Internal-Token", "internal-test-token"))
                .andExpect(status().isNoContent)
        }
        verify(userDeletionService, times(2)).deleteUser("deleted-user")
    }

    @Test
    fun `공백 또는 128자를 초과하는 사용자 ID는 400이며 삭제하지 않는다`() {
        listOf(" ", "u".repeat(129)).forEach { userId ->
            mockMvc
                .perform(delete("/api/v1/internal/users/{userId}", userId).header("X-Internal-Token", "internal-test-token"))
                .andExpect(status().isBadRequest)
        }
        verifyNoInteractions(userDeletionService)
    }

    @Test
    fun `내부 토큰 불일치와 누락은 403이며 삭제를 실행하지 않는다`() {
        mockMvc
            .perform(delete("/api/v1/internal/users/deleted-user").header("X-Internal-Token", "invalid-token"))
            .andExpect(status().isForbidden)
        mockMvc
            .perform(delete("/api/v1/internal/users/deleted-user"))
            .andExpect(status().isForbidden)
        verifyNoInteractions(userDeletionService)
    }
}
