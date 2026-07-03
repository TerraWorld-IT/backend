package com.terraworld.api.exchange

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import io.terraworld.api.model.ExchangeRequest
import io.terraworld.api.model.ExchangeResult
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

/**
 * ExchangeController (POST /exchange) MVC 검증 — 낙서장 P1 7화폐 directed exchange.
 */
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
    fun `POST _api_v1_exchange 200`() {
        whenever(exchangeService.exchange(eq(TEST_USER_ID), eq("DEW"), eq("COIN"), eq(100L))).thenReturn(
            ExchangeResult(
                from = "DEW",
                to = "COIN",
                fromAmount = 100,
                grossToAmount = 10,
                feeAmount = 1,
                toAmount = 9,
                rate = "10:1",
                updatedCurrency = stubCurrency(),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/exchange")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ExchangeRequest(from = "DEW", to = "COIN", amount = 100),
                        ),
                    ),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.from").value("DEW"))
            .andExpect(jsonPath("$.toAmount").value(9))
            .andExpect(jsonPath("$.feeAmount").value(1))
    }

    @Test
    fun `POST _api_v1_exchange 400 when pair not allowed`() {
        whenever(exchangeService.exchange(any(), any(), any(), any()))
            .thenThrow(BusinessException(ErrorCode.PAIR_NOT_ALLOWED))

        mockMvc
            .perform(
                post("/api/v1/exchange")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ExchangeRequest(from = "COIN", to = "RUBY", amount = 100),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PAIR_NOT_ALLOWED"))
    }

    @Test
    fun `POST _api_v1_exchange 400 when insufficient funds`() {
        whenever(exchangeService.exchange(any(), any(), any(), any()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))

        mockMvc
            .perform(
                post("/api/v1/exchange")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ExchangeRequest(from = "DEW", to = "COIN", amount = 9999),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    }

    private fun stubCurrency() =
        CurrencyResponse(
            balances =
                listOf(
                    CurrencyBalance(code = "COIN", amount = 109),
                    CurrencyBalance(code = "DEW", amount = 0),
                ),
        )
}
