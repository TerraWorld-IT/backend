package com.terraworld.api.record.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Positive

@Schema(description = "활동 기록 생성 요청 (service-internal)")
data class CreateRecordRequest(
    val categoryId: Long,
    @field:Positive val duration: Int? = null,
    val note: String? = null,
    val photoUrl: String? = null,
    val partnerUserId: String? = null,
)
