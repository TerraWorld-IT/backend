package com.terraworld.api.upload

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.UploadApi
import io.terraworld.api.model.PhotoUploadResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.net.URI
import java.time.OffsetDateTime

/**
 * UploadApi (1 endpoint — uploadPhoto) implement. spec PR #12 의 tag
 * 분리 (Record ↔ Upload) 결과 UploadApi 신규 생성됨.
 *
 * PhotoUploadService 시그니처 (local PhotoUploadResponse) 는 그대로 두고
 * controller 에서 generated DTO (URI / OffsetDateTime) 로 변환 —
 * PhotoUploadServiceTest 가 String 으로 비교하므로 service 변경 시 테스트
 * 깨짐. 후속 슬라이스에서 service 도 generated DTO 직접 반환하도록
 * 마이그레이션 가능.
 */
@Tag(name = "Upload", description = "사진 업로드 API")
@RestController
@RequestMapping("/api/v1")
class PhotoUploadController(
    private val photoUploadService: PhotoUploadService,
) : UploadApi {
    @Operation(summary = "사진 업로드", description = "활동 기록 첨부용 이미지 업로드 (jpeg/png/webp, 최대 5MB)")
    override fun uploadPhoto(file: MultipartFile): ResponseEntity<PhotoUploadResponse> {
        val local = photoUploadService.upload(file)
        return ResponseEntity.ok(
            PhotoUploadResponse(
                // dataURL ("data:image/...;base64,...") 도 URI scheme 으로 valid.
                photoUrl = URI.create(local.photoUrl),
                expiresAt = local.expiresAt?.let { OffsetDateTime.parse(it) },
            ),
        )
    }
}
