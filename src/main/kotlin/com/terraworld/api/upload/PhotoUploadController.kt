package com.terraworld.api.upload

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.UploadApi
import io.terraworld.api.model.PhotoUploadResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * UploadApi (1 endpoint — uploadPhoto) implement. spec PR #12 의 tag
 * 분리 (Record ↔ Upload) 결과 UploadApi 신규 생성됨.
 *
 * Service DTO migration (ARCH-008-phase-4) 후: PhotoUploadService 가 generated
 * [PhotoUploadResponse] 직접 반환. controller mapping 제거.
 */
@Tag(name = "Upload", description = "사진 업로드 API")
@RestController
@RequestMapping("/api/v1")
class PhotoUploadController(
    private val photoUploadService: PhotoUploadService,
) : UploadApi {
    @Operation(summary = "사진 업로드", description = "활동 기록 첨부용 이미지 업로드 (jpeg/png/webp, 최대 5MB)")
    override fun uploadPhoto(file: MultipartFile): ResponseEntity<PhotoUploadResponse> {
        val response = photoUploadService.upload(file)
        return ResponseEntity.ok(response)
    }
}
