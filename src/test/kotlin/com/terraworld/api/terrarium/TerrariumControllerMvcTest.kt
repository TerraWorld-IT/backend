package com.terraworld.api.terrarium

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.BackgroundInfo
import io.terraworld.api.model.HeartResponse
import io.terraworld.api.model.PlacedItemDetail
import io.terraworld.api.model.PlacementItem
import io.terraworld.api.model.PlacementRequest
import io.terraworld.api.model.TerrariumResponse
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(TerrariumController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class TerrariumControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var terrariumService: TerrariumService

    @MockBean private lateinit var tierService: TierService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_terrarium 200`() {
        whenever(terrariumService.getTerrarium(eq(TEST_USER_ID))).thenReturn(stubTerrarium())

        mockMvc
            .perform(get("/api/v1/terrarium"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.terrariumId").value(1))
            .andExpect(jsonPath("$.maxSlots").value(6))
            .andExpect(jsonPath("$.tier").value("GLASS_JAR"))
    }

    @Test
    fun `PUT _api_v1_terrarium_placements 200`() {
        whenever(terrariumService.updatePlacements(eq(TEST_USER_ID), any())).thenReturn(stubTerrarium())

        val payload = PlacementRequest(placedItems = listOf(PlacementItem(itemId = 1L, slotId = 0)))

        mockMvc
            .perform(
                put("/api/v1/terrarium/placements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.placedItems[0].itemId").value(10))
    }

    @Test
    fun `POST _api_v1_terrarium_heart 200`() {
        whenever(terrariumService.heartClick(eq(TEST_USER_ID)))
            .thenReturn(HeartResponse(reward = 0.1, updatedBasicCoins = 100.1))

        mockMvc
            .perform(post("/api/v1/terrarium/heart"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reward").value(0.1))
            .andExpect(jsonPath("$.updatedBasicCoins").value(100.1))
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `GET _api_v1_terrarium 404 when terrarium not found`() {
        whenever(terrariumService.getTerrarium(eq(TEST_USER_ID)))
            .thenThrow(BusinessException(ErrorCode.TERRARIUM_NOT_FOUND))

        mockMvc
            .perform(get("/api/v1/terrarium"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("TERRARIUM_NOT_FOUND"))
    }

    @Test
    fun `PUT _api_v1_terrarium_placements 400 when invalid slot`() {
        whenever(terrariumService.updatePlacements(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.INVALID_SLOT))

        val payload = PlacementRequest(placedItems = listOf(PlacementItem(itemId = 1L, slotId = 99)))

        mockMvc
            .perform(
                put("/api/v1/terrarium/placements")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(payload)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_SLOT"))
    }

    private fun stubTerrarium() =
        TerrariumResponse(
            terrariumId = 1L,
            background = BackgroundInfo(id = 1L, name = "기본 배경", assetUrl = "/bg.png"),
            placedItems =
                listOf(
                    PlacedItemDetail(
                        id = 1L,
                        itemId = 10L,
                        itemImage = "/item.png",
                        itemName = "테스트 아이템",
                        itemLayout = PlacedItemDetail.ItemLayout.forValue("FOREGROUND"),
                        isAnimated = false,
                        itemSlug = "item-10",
                        slotId = 0,
                    ),
                ),
            maxSlots = 6,
            tier = "GLASS_JAR",
        )
}
