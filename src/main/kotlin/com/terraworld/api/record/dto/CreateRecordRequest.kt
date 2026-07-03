package com.terraworld.api.record.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

@Schema(description = "활동 기록 생성 요청 (service-internal)")
data class CreateRecordRequest(
    val categoryId: Long,
    // 낙서장 P1: 일상 하위 타입(PHOTO/DIARY/FOCUS/DISTANCE). 지정 시 dailyType 기준 보상.
    val dailyType: com.terraworld.domain.record.DailyType? = null,
    @field:Positive val duration: Int? = null,
    val note: String? = null,
    val photoUrl: String? = null,
    val partnerUserId: String? = null,
)
