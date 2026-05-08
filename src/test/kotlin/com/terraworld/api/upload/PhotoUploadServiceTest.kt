package com.terraworld.api.upload

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockMultipartFile
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * PhotoUploadService 의 검증 분기 테스트.
 *
 * dataURL PoC 동작 (Phase 4 에서 S3 통합으로 교체) 의 contract:
 *   - 빈 파일 거부 (BAD_REQUEST)
 *   - 5MB 초과 거부 (PAYLOAD_TOO_LARGE)
 *   - 허용 외 MIME 거부 (BAD_REQUEST)
 *   - 정상 입력 → dataUrl 응답
 */
class PhotoUploadServiceTest {
    private lateinit var service: PhotoUploadService

    @BeforeEach
    fun setup() {
        service = PhotoUploadService()
    }

    @Test
    fun `빈 파일은 BAD_REQUEST 로 거부한다`() {
        val empty = MockMultipartFile("file", "empty.jpg", "image/jpeg", ByteArray(0))

        val ex =
            assertThrows<BusinessException> {
                service.upload(empty)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `5MB 초과 파일은 PAYLOAD_TOO_LARGE 로 거부한다`() {
        val oversize =
            MockMultipartFile(
                "file",
                "huge.jpg",
                "image/jpeg",
                ByteArray(5 * 1024 * 1024 + 1) { 0xff.toByte() },
            )

        val ex =
            assertThrows<ResponseStatusException> {
                service.upload(oversize)
            }
        assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, ex.statusCode)
    }

    @Test
    fun `허용 외 MIME(application_pdf)는 BAD_REQUEST 로 거부한다`() {
        val pdf =
            MockMultipartFile(
                "file",
                "doc.pdf",
                "application/pdf",
                byteArrayOf(0x25, 0x50, 0x44, 0x46),
            )

        val ex =
            assertThrows<BusinessException> {
                service.upload(pdf)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `image_jpeg 정상 magic byte 는 허용한다`() {
        val jpeg =
            MockMultipartFile(
                "file",
                "ok.jpg",
                "image/jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()),
            )
        val response = service.upload(jpeg)
        assertTrue(response.photoUrl.toString().startsWith("data:image/jpeg;base64,"))
        assertNull(response.expiresAt)
    }

    @Test
    fun `image_png 정상 magic byte 는 허용한다`() {
        val pngHeader =
            byteArrayOf(
                0x89.toByte(),
                0x50,
                0x4E,
                0x47,
                0x0D,
                0x0A,
                0x1A,
                0x0A,
            )
        val png = MockMultipartFile("file", "ok.png", "image/png", pngHeader + byteArrayOf(0x00, 0x01))
        val response = service.upload(png)
        assertTrue(response.photoUrl.toString().startsWith("data:image/png;base64,"))
    }

    @Test
    fun `image_webp 정상 magic byte (RIFF WEBP) 는 허용한다`() {
        // RIFF + 4 byte size + WEBP
        val webp =
            MockMultipartFile(
                "file",
                "ok.webp",
                "image/webp",
                byteArrayOf(
                    'R'.code.toByte(),
                    'I'.code.toByte(),
                    'F'.code.toByte(),
                    'F'.code.toByte(),
                    0,
                    0,
                    0,
                    0,
                    'W'.code.toByte(),
                    'E'.code.toByte(),
                    'B'.code.toByte(),
                    'P'.code.toByte(),
                ),
            )
        val response = service.upload(webp)
        assertTrue(response.photoUrl.toString().startsWith("data:image/webp;base64,"))
    }

    @Test
    fun `대문자 MIME 도 정규화 후 허용한다 (정상 magic byte)`() {
        val upper =
            MockMultipartFile(
                "file",
                "ok.jpg",
                "IMAGE/JPEG",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            )
        val response = service.upload(upper)
        assertTrue(response.photoUrl.toString().startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun `image_jpeg MIME 위조 — magic byte 가 PNG signature 면 거부`() {
        val faked =
            MockMultipartFile(
                "file",
                "fake.jpg",
                "image/jpeg",
                byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A),
            )
        val ex =
            assertThrows<BusinessException> {
                service.upload(faked)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `image_jpeg MIME 위조 — 의미없는 byte 면 거부`() {
        val random =
            MockMultipartFile(
                "file",
                "random.jpg",
                "image/jpeg",
                byteArrayOf(0x10, 0x20, 0x30, 0x40),
            )
        val ex =
            assertThrows<BusinessException> {
                service.upload(random)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }
}
