package com.terraworld.api.category

import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(CategoryController::class)
@AutoConfigureMockMvc(addFilters = false)
class CategoryControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var categoryRepository: CategoryRepository

    @MockBean private lateinit var userTokenRepository: UserTokenRepository

    @MockBean private lateinit var userRepository: UserRepository

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_categories 200 + system 4종 반환`() {
        whenever(categoryRepository.findAllByIsActiveTrueAndIsCustomFalse())
            .thenReturn(
                listOf(
                    stubCategory(1L, "산책", "WALK"),
                    stubCategory(2L, "독서", "READ"),
                ),
            )

        mockMvc
            .perform(get("/api/v1/categories"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.categories[0].name").value("산책"))
            .andExpect(jsonPath("$.categories[1].name").value("독서"))
    }

    private fun stubCategory(
        id: Long,
        name: String,
        tokenName: String,
    ) = Category(
        id = id,
        name = name,
        color = "#FFAA00",
        tokenName = tokenName,
        emoji = "🚶",
        baseCoinReward = 10,
        baseTokenReward = 5,
        dailyLimit = 5,
        isActive = true,
        isCustom = false,
        ownerUserId = null,
    )
}
