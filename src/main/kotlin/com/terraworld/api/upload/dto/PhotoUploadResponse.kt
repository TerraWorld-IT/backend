package com.terraworld.api.upload.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "사진 업로드 응답")
data class PhotoUploadResponse(
    @Schema(example = "https://cdn.terraworld.app/photos/abc123.jpg")
    val photoUrl: String,
    @Schema(nullable = true, description = "Presigned URL 만료 시각. 영구 저장 시 null.")
    val expiresAt: String?,
)
