package com.terraworld.api.purchase

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import io.terraworld.api.model.PurchaseResponse
import io.terraworld.api.model.PurchasedItemInfo
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
import io.terraworld.api.model.PurchaseRequest as ApiPurchaseRequest

@WebMvcTest(PurchaseController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class PurchaseControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var purchaseService: PurchaseService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_purchases 200`() {
        whenever(purchaseService.purchase(eq(TEST_USER_ID), any())).thenReturn(
            PurchaseResponse(
                purchasedItem = PurchasedItemInfo(id = 1L, slug = "test-item", name = "테스트"),
                updatedCurrency = stubCurrency(),
                ownedItems = listOf("test-item"),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/purchases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiPurchaseRequest(itemId = 1L))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.purchasedItem.id").value(1))
            .andExpect(jsonPath("$.ownedItems[0]").value("test-item"))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST _api_v1_purchases 404 when item not found`() {
        whenever(purchaseService.purchase(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.ITEM_NOT_FOUND))

        mockMvc
            .perform(
                post("/api/v1/purchases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiPurchaseRequest(itemId = 9999L))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("ITEM_NOT_FOUND"))
    }

    @Test
    fun `POST _api_v1_purchases 409 when already owned`() {
        whenever(purchaseService.purchase(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.ALREADY_OWNED))

        mockMvc
            .perform(
                post("/api/v1/purchases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiPurchaseRequest(itemId = 1L))),
            ).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("ALREADY_OWNED"))
    }

    @Test
    fun `POST _api_v1_purchases 400 when insufficient funds`() {
        whenever(purchaseService.purchase(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))

        mockMvc
            .perform(
                post("/api/v1/purchases")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiPurchaseRequest(itemId = 1L))),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INSUFFICIENT_FUNDS"))
    }

    private fun stubCurrency() =
        CurrencyResponse(
            balances =
                listOf(
                    CurrencyBalance(code = "COIN", amount = 50),
                    CurrencyBalance(code = "RUBY", amount = 0),
                    CurrencyBalance(code = "DEW", amount = 0),
                    CurrencyBalance(code = "SUN", amount = 0),
                    CurrencyBalance(code = "BOLT", amount = 0),
                    CurrencyBalance(code = "WIND", amount = 0),
                ),
        )
}
