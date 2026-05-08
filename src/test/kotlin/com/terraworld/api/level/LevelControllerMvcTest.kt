package com.terraworld.api.level

import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.domain.level.LevelConfig
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
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
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@WebMvcTest(LevelController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class LevelControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var levelConfigRepository: LevelConfigRepository

    @MockBean private lateinit var userRepository: UserRepository

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_levels 200`() {
        whenever(userRepository.findById(eq(TEST_USER_ID))).thenReturn(
            Optional.of(
                User(id = TEST_USER_ID, nickname = "tester", role = UserRole.USER, level = 3, totalExp = 250),
            ),
        )
        whenever(levelConfigRepository.findAll()).thenReturn(
            listOf(
                LevelConfig(level = 1, requiredExp = 0, maxItems = 20),
                LevelConfig(level = 2, requiredExp = 100, maxItems = 22),
                LevelConfig(level = 3, requiredExp = 200, maxItems = 24),
            ),
        )

        mockMvc
            .perform(get("/api/v1/levels"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.currentLevel").value(3))
            .andExpect(jsonPath("$.currentExp").value(250))
            .andExpect(jsonPath("$.levels.length()").value(3))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `GET _api_v1_levels 404 when user not found`() {
        whenever(userRepository.findById(eq(TEST_USER_ID))).thenReturn(Optional.empty())

        mockMvc
            .perform(get("/api/v1/levels"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }
}
