package com.terraworld.api.user

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.userdevice.DeviceRegistrationResponse
import com.terraworld.api.userdevice.UserDeviceService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import io.terraworld.api.model.EntitlementsResponse
import io.terraworld.api.model.PlacedItemResponse
import io.terraworld.api.model.UserMeResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
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
import java.time.OffsetDateTime
import io.terraworld.api.model.DeviceRegistrationRequest as ApiDeviceRegistrationRequest

/**
 * UserController (UserApi 구현) smoke test.
 *
 * 본 테스트는 spec 의 contract (path / status code / 핵심 필드) 가 maintainable
 * 하다는 것만 검증한다. 비즈니스 로직 디테일은 UserService 단위 테스트에서
 * 별도 다룬다.
 *
 * `addFilters = false` 로 JwtAuthenticationFilter / RateLimitFilter 비활성화
 * → AbstractMvcTest 가 SecurityContextHolder 에 직접 인증 주입.
 */
@WebMvcTest(UserController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class UserControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var userService: UserService

    @MockBean private lateinit var userDeviceService: UserDeviceService

    // SecurityFilterChain 이 의존하는 빈들 — addFilters=false 로 chain 자체는 안 도는데,
    // bean 의존성 그래프 상 mock 으로 채워야 ApplicationContext 가 뜬다.
    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_users_me 200 + 핵심 필드 반환`() {
        whenever(userService.getMe(eq(TEST_USER_ID), eq(TEST_USER_EMAIL)))
            .thenReturn(
                UserMeResponse(
                    userId = TEST_USER_ID,
                    email = TEST_USER_EMAIL,
                    nickname = "테스트유저",
                    role = UserMeResponse.Role.USER,
                    currency =
                        CurrencyResponse(
                            balances =
                                listOf(
                                    CurrencyBalance(code = "COIN", amount = 100),
                                    CurrencyBalance(code = "RUBY", amount = 5),
                                ),
                        ),
                    ownedItems = emptyList(),
                    placedItems = emptyList<PlacedItemResponse>(),
                    entitlements = EntitlementsResponse(freePlacement = false, premiumThemes = false),
                ),
            )

        mockMvc
            .perform(get("/api/v1/users/me"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(TEST_USER_ID))
            .andExpect(jsonPath("$.email").value(TEST_USER_EMAIL))
            .andExpect(jsonPath("$.role").value("USER"))
            .andExpect(jsonPath("$.currency.balances[0].code").value("COIN"))
            .andExpect(jsonPath("$.currency.balances[0].amount").value(100))
    }

    @Test
    fun `POST _api_v1_users_me_devices 200 + deviceId 반환`() {
        whenever(userDeviceService.upsert(eq(TEST_USER_ID), any()))
            .thenReturn(
                DeviceRegistrationResponse(
                    deviceId = 42L,
                    registeredAt = OffsetDateTime.parse("2026-05-07T00:00:00Z"),
                ),
            )

        val payload =
            ApiDeviceRegistrationRequest(
                token = "fcm-token-${"x".repeat(140)}",
                platform = ApiDeviceRegistrationRequest.Platform.ANDROID,
                appVersion = "1.0.0",
            )

        mockMvc
            .perform(
                post("/api/v1/users/me/devices")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.deviceId").value(42))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `GET _api_v1_users_me 404 when user not found`() {
        whenever(userService.getMe(eq(TEST_USER_ID), eq(TEST_USER_EMAIL)))
            .thenThrow(BusinessException(ErrorCode.USER_NOT_FOUND))

        mockMvc
            .perform(get("/api/v1/users/me"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }
}
