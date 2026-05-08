package com.terraworld.api.record.dto

import io.swagger.v3.oas.annotations.media.Schema
import io.terraworld.api.model.CurrencyResponse
import jakarta.validation.constraints.Positive
import java.time.LocalDate
import java.time.LocalDateTime

@Schema(description = "활동 기록 생성 요청")
data class CreateRecordRequest(
    @Schema(example = "1", description = "카테고리 ID (1=산책, 2=독서, 3=러닝, 4=낙서)")
    val categoryId: Long,
    @field:Positive
    @Schema(example = "30", description = "활동 시간(분), 선택사항")
    val duration: Int? = null,
    @Schema(example = "공원에서 산책했다", description = "메모, 선택사항")
    val note: String? = null,
    @Schema(description = "첨부 사진 URL (선택)")
    val photoUrl: String? = null,
    @Schema(description = "함께 기록할 친구의 userId. 지정 시 양쪽 모두 보상 가산.")
    val partnerUserId: String? = null,
)

@Schema(description = "활동 기록 응답")
data class RecordResponse(
    val id: Long,
    val categoryId: Long,
    val categoryName: String,
    val categoryEmoji: String?,
    val memo: String?,
    val duration: Int?,
    val recordedDate: LocalDate,
    val createdAt: LocalDateTime,
    @Schema(description = "첨부 사진 URL (업로드 시 등록)")
    val photoUrl: String? = null,
)

@Schema(description = "기록 생성 + 보상 응답")
data class CreateRecordResponse(
    val record: RecordResponse,
    val reward: RewardInfo,
    val updatedCurrency: CurrencyResponse,
)

@Schema(description = "보상 정보")
data class RewardInfo(
    val basicCoins: Int,
    val categoryTokens: Int,
    val experienceGained: Int,
)

@Schema(description = "활동 통계")
data class StatisticsResponse(
    val todayRecords: Long,
    val thisWeekRecords: Long,
    val totalRecords: Long,
    val byCategory: List<CategoryCount>,
)

data class CategoryCount(
    val categoryId: Long,
    val categoryName: String,
    val emoji: String?,
    val count: Long,
)
