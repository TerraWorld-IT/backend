package com.terraworld.api.upload

import com.terraworld.api.upload.dto.PhotoUploadResponse
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(PhotoUploadController::class)
@AutoConfigureMockMvc(addFilters = false)
class PhotoUploadControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var photoUploadService: PhotoUploadService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `POST _api_v1_uploads_photo 200`() {
        whenever(photoUploadService.upload(any())).thenReturn(
            PhotoUploadResponse(
                photoUrl = "data:image/jpeg;base64,abc123",
                expiresAt = null,
            ),
        )

        val file =
            MockMultipartFile(
                "file",
                "ok.jpg",
                "image/jpeg",
                byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            )

        mockMvc
            .perform(multipart("/api/v1/uploads/photo").file(file))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.photoUrl").value("data:image/jpeg;base64,abc123"))
    }
}
