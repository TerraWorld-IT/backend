package com.terraworld.api.upload

import com.terraworld.api.upload.dto.PhotoUploadResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * 사진 업로드 컨트롤러.
 *
 * spec : openapi/spec/paths/uploads-photo.yaml
 *
 * 현재는 generated openapi-backend interface 가 도착하기 전이라 plain
 * controller. spec sync 가 완료되면 generated UploadApi 를 implement 하도록
 * 마이그레이션.
 */
@RestController
@RequestMapping("/api/v1/uploads")
@Tag(name = "Record", description = "사진 업로드 (활동 기록 첨부용)")
class PhotoUploadController(
    private val photoUploadService: PhotoUploadService,
) {
    @PostMapping("/photo", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadPhoto(
        @RequestParam("file") file: MultipartFile,
    ): PhotoUploadResponse = photoUploadService.upload(file)
}
