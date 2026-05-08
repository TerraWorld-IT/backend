package com.terraworld.api.record

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.api.upload.PhotoUrlValidator
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.CategoryCount
import io.terraworld.api.model.CreateRecordResponse
import io.terraworld.api.model.CurrencyResponse
import io.terraworld.api.model.RecordResponse
import io.terraworld.api.model.RewardInfo
import io.terraworld.api.model.StatisticsResponse
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.time.OffsetDateTime
import io.terraworld.api.model.CreateRecordRequest as ApiCreateRecordRequest

@WebMvcTest(RecordController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class RecordControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var recordService: RecordService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    // SF-005 follow-up: photoUrl 화이트리스트 검증기 — happy path 는 mockito default (no-op).
    // negative case 에서만 doThrow 로 violation 흉내.
    @MockBean private lateinit var photoUrlValidator: PhotoUrlValidator

    @Test
    fun `POST _api_v1_records 201`() {
        whenever(recordService.createRecord(eq(TEST_USER_ID), any())).thenReturn(
            CreateRecordResponse(
                record = stubRecord(),
                reward = RewardInfo(basicCoins = 20, categoryTokens = 10, experienceGained = 5),
                updatedCurrency = stubCurrency(),
            ),
        )

        mockMvc
            .perform(
                post("/api/v1/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiCreateRecordRequest(categoryId = 1L))),
            ).andExpect(status().isCreated)
            .andExpect(jsonPath("$.record.id").value(1))
            .andExpect(jsonPath("$.reward.basicCoins").value(20))
    }

    @Test
    fun `GET _api_v1_records 200`() {
        whenever(recordService.getRecords(eq(TEST_USER_ID), eq(null), eq(null), eq(null), any()))
            .thenReturn(PageImpl(listOf(stubRecord()), PageRequest.of(0, 20), 1))

        mockMvc
            .perform(get("/api/v1/records"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.content[0].id").value(1))
            .andExpect(jsonPath("$.totalElements").value(1))
    }

    @Test
    fun `GET _api_v1_records_statistics 200`() {
        whenever(recordService.getStatistics(eq(TEST_USER_ID))).thenReturn(
            StatisticsResponse(
                todayRecords = 1,
                thisWeekRecords = 3,
                totalRecords = 10,
                byCategory = listOf(CategoryCount(categoryId = 1L, categoryName = "산책", emoji = "🚶", count = 5)),
            ),
        )

        mockMvc
            .perform(get("/api/v1/records/statistics"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalRecords").value(10))
            .andExpect(jsonPath("$.byCategory[0].count").value(5))
    }

    @Test
    fun `DELETE _api_v1_records_id 200`() {
        mockMvc
            .perform(delete("/api/v1/records/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `POST _api_v1_records 400 INVALID_PHOTO_URL when validator rejects`() {
        // photoUrl 화이트리스트 위반 시 controller 가 service 호출 전에 BusinessException → 400
        doThrow(BusinessException(ErrorCode.INVALID_PHOTO_URL))
            .whenever(photoUrlValidator)
            .requireAllowed(any())

        mockMvc
            .perform(
                post("/api/v1/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            ApiCreateRecordRequest(
                                categoryId = 1L,
                                photoUrl = java.net.URI.create("https://attacker.example/evil.jpg"),
                            ),
                        ),
                    ),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_PHOTO_URL"))
    }

    @Test
    fun `POST _api_v1_records 404 when category not found`() {
        // service 가 BusinessException(CATEGORY_NOT_FOUND) throw → GlobalExceptionHandler 가 404 매핑
        whenever(recordService.createRecord(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.CATEGORY_NOT_FOUND))

        mockMvc
            .perform(
                post("/api/v1/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiCreateRecordRequest(categoryId = 9999L))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"))
    }

    @Test
    fun `POST _api_v1_records 429 when daily limit exceeded`() {
        whenever(recordService.createRecord(eq(TEST_USER_ID), any()))
            .thenThrow(BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED))

        mockMvc
            .perform(
                post("/api/v1/records")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(ApiCreateRecordRequest(categoryId = 1L))),
            ).andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.code").value("DAILY_LIMIT_EXCEEDED"))
    }

    @Test
    fun `DELETE _api_v1_records_id 404 when record not found`() {
        // RecordService.deleteRecord 가 RECORD_NOT_FOUND throw → 404
        whenever(recordService.deleteRecord(eq(TEST_USER_ID), eq(9999L)))
            .thenThrow(BusinessException(ErrorCode.RECORD_NOT_FOUND))

        mockMvc
            .perform(delete("/api/v1/records/9999"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("RECORD_NOT_FOUND"))
    }

    private fun stubRecord() =
        RecordResponse(
            id = 1L,
            categoryId = 1L,
            categoryName = "산책",
            recordedDate = LocalDate.parse("2026-05-07"),
            createdAt = OffsetDateTime.parse("2026-05-07T10:00:00Z"),
            categoryEmoji = "🚶",
            memo = "오늘 산책",
            duration = 30,
            photoUrl = null,
        )

    private fun stubCurrency() =
        CurrencyResponse(
            basicCoins = 120.0,
            specialCoins = 0.0,
            walkTokens = 10.0,
            readTokens = 0.0,
            runTokens = 0.0,
            drawTokens = 0.0,
        )
}
