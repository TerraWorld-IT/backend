package com.terraworld.api.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.exchange.dto.ExchangeInfo
import com.terraworld.api.exchange.dto.ExchangeResponse
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import io.terraworld.api.model.SpecialToBasicRequest as ApiSpecialToBasicRequest
import io.terraworld.api.model.TokenExchangeRequest as ApiTokenExchangeRequest

@WebMvcTest(ExchangeController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class ExchangeControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var exchangeService: ExchangeService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_exchange_special-to-basic 200`() {
        whenever(exchangeService.specialToBasic(eq(TEST_USER_ID), any())).thenReturn(
            ExchangeResponse(
                exchanged = ExchangeInfo("SPECIAL_COIN", 5, "BASIC_COIN", 10, 2.0),
                updatedCurrency = stubCurrency(),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/exchange/special-to-basic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiSpecialToBasicRequest(amount = 5))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.exchanged.fromAmount").value(5))
            .andExpect(jsonPath("$.exchanged.toAmount").value(10))
    }

    @Test
    fun `POST _api_v1_exchange_tokens 200`() {
        whenever(exchangeService.exchangeTokens(eq(TEST_USER_ID), any())).thenReturn(
            ExchangeResponse(
                exchanged = ExchangeInfo("산책", 10, "독서", 5, 0.5),
                updatedCurrency = stubCurrency(),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/exchange/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ApiTokenExchangeRequest(fromCategoryId = 1L, toCategoryId = 2L, amount = 10),
                        ),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.exchanged.fromType").value("산책"))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST _api_v1_exchange_special-to-basic 400 when insufficient funds`() {
        whenever(exchangeService.specialToBasic(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))

        mockMvc
            .perform(
                post("/api/v1/exchange/special-to-basic")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiSpecialToBasicRequest(amount = 99999))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    }

    @Test
    fun `POST _api_v1_exchange_tokens 400 when same category`() {
        whenever(exchangeService.exchangeTokens(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.SAME_CATEGORY_EXCHANGE))

        mockMvc
            .perform(
                post("/api/v1/exchange/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ApiTokenExchangeRequest(fromCategoryId = 1L, toCategoryId = 1L, amount = 10),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SAME_CATEGORY_EXCHANGE"))
    }

    @Test
    fun `POST _api_v1_exchange_tokens 400 when exchange rate not found`() {
        whenever(exchangeService.exchangeTokens(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND))

        mockMvc
            .perform(
                post("/api/v1/exchange/tokens")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ApiTokenExchangeRequest(fromCategoryId = 1L, toCategoryId = 99L, amount = 10),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("EXCHANGE_RATE_NOT_FOUND"))
    }

    private fun stubCurrency() =
        CurrencyResponse(
            basicCoins = 100.0,
            specialCoins = 0.0,
            walkTokens = 0.0,
            readTokens = 5.0,
            runTokens = 0.0,
            drawTokens = 0.0,
        )
}
