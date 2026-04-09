package com.terraworld.api.note.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.NotBlank

@Schema(description = "메모 저장 요청")
data class SaveNoteRequest(
    @field:NotBlank
    @Schema(example = "오늘 날씨가 좋았다")
    val note: String,
)

@Schema(description = "메모 응답")
data class NoteResponse(
    val date: String,
    val note: String,
)
