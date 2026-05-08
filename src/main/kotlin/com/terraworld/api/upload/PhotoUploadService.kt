package com.terraworld.api.upload

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import io.terraworld.api.model.PhotoUploadResponse
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.multipart.MultipartFile
import org.springframework.web.server.ResponseStatusException
import java.net.URI
import java.util.Base64

@Service
class PhotoUploadService {
    companion object {
        private const val MAX_BYTES = 5L * 1024 * 1024 // 5MB
        private val ALLOWED_MIMES = setOf("image/jpeg", "image/png", "image/webp")
    }

    /**
     * PoC 구현 — multipart 파일을 dataURL(base64) 로 변환해 그대로 photoUrl 로 반환.
     *
     * 운영 단계 (Phase 4) 에서는 외부 객체 스토리지(S3 호환) 로 교체:
     *   1) presigned PUT URL 발급 → 클라이언트가 직접 업로드
     *   2) 또는 서버가 파일 받아 S3 PutObject → 공개 CDN URL 반환
     *
     * dataURL 방식의 한계:
     *   - DB 비대 (5MB 이미지 → ~7MB base64 텍스트)
     *   - SSR HTML payload 비대
     *   - 캐싱·CDN 활용 불가
     *
     * Service DTO migration (ARCH-008-phase-4): 반환 타입을 generated `PhotoUploadResponse`
     * 로 직접 변경. controller 의 String→URI / String→OffsetDateTime 매퍼 제거.
     * dataURL 은 `data:image/...` scheme 으로 valid URI — `URI.create()` 안전.
     */
    fun upload(file: MultipartFile): PhotoUploadResponse {
        if (file.isEmpty) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }
        if (file.size > MAX_BYTES) {
            throw ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "파일은 5MB 를 초과할 수 없습니다")
        }
        val mime = (file.contentType ?: "").lowercase()
        if (mime !in ALLOWED_MIMES) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }
        // Content-Type 헤더는 클라이언트가 위조 가능. magic byte signature 로 실제 포맷 재검증.
        val bytes = file.bytes
        if (!verifyMagicBytes(bytes, mime)) {
            throw BusinessException(ErrorCode.BAD_REQUEST)
        }

        val base64 = Base64.getEncoder().encodeToString(bytes)
        val dataUrl = "data:$mime;base64,$base64"
        return PhotoUploadResponse(
            photoUrl = URI.create(dataUrl),
            expiresAt = null,
        )
    }

    /**
     * 첫 8 byte 의 file signature 와 선언된 MIME 의 정합 검증.
     *  - JPEG : FF D8 FF
     *  - PNG  : 89 50 4E 47 0D 0A 1A 0A
     *  - WEBP : RIFF...WEBP (offset 0~3 == "RIFF", 8~11 == "WEBP")
     */
    private fun verifyMagicBytes(
        bytes: ByteArray,
        mime: String,
    ): Boolean =
        when (mime) {
            "image/jpeg" ->
                bytes.size >= 3 &&
                    bytes[0] == 0xFF.toByte() &&
                    bytes[1] == 0xD8.toByte() &&
                    bytes[2] == 0xFF.toByte()
            "image/png" ->
                bytes.size >= 8 &&
                    bytes[0] == 0x89.toByte() &&
                    bytes[1] == 0x50.toByte() &&
                    bytes[2] == 0x4E.toByte() &&
                    bytes[3] == 0x47.toByte() &&
                    bytes[4] == 0x0D.toByte() &&
                    bytes[5] == 0x0A.toByte() &&
                    bytes[6] == 0x1A.toByte() &&
                    bytes[7] == 0x0A.toByte()
            "image/webp" ->
                bytes.size >= 12 &&
                    bytes[0] == 'R'.code.toByte() &&
                    bytes[1] == 'I'.code.toByte() &&
                    bytes[2] == 'F'.code.toByte() &&
                    bytes[3] == 'F'.code.toByte() &&
                    bytes[8] == 'W'.code.toByte() &&
                    bytes[9] == 'E'.code.toByte() &&
                    bytes[10] == 'B'.code.toByte() &&
                    bytes[11] == 'P'.code.toByte()
            else -> false
        }
}
