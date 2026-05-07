package com.terraworld.api.purchase

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.purchase.dto.PurchaseResponse
import com.terraworld.api.purchase.dto.PurchasedItemInfo
import com.terraworld.api.user.dto.CurrencyResponse
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
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import io.terraworld.api.model.PurchaseRequest as ApiPurchaseRequest

@WebMvcTest(PurchaseController::class)
@AutoConfigureMockMvc(addFilters = false)
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

    private fun stubCurrency() =
        CurrencyResponse(
            basicCoins = 50.0,
            specialCoins = 0.0,
            walkTokens = 0.0,
            readTokens = 0.0,
            runTokens = 0.0,
            drawTokens = 0.0,
        )
}
