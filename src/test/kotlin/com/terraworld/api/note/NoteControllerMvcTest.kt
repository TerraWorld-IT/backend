package com.terraworld.api.note

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.common.exception.GlobalExceptionHandler
import com.terraworld.domain.note.DayNote
import com.terraworld.domain.note.DayNoteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import io.terraworld.api.model.SaveNoteRequest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.LocalDate
import java.util.Optional

@WebMvcTest(NoteController::class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler::class)
class NoteControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @Autowired private lateinit var objectMapper: ObjectMapper

    @MockBean private lateinit var dayNoteRepository: DayNoteRepository

    @MockBean private lateinit var userRepository: UserRepository

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_notes_date 200`() {
        val date = LocalDate.parse("2026-05-07")
        val user = stubUser()
        val note = DayNote(user = user, noteDate = date, note = "테스트 메모")
        whenever(dayNoteRepository.findByUserIdAndNoteDate(eq(TEST_USER_ID), eq(date)))
            .thenReturn(Optional.of(note))

        mockMvc
            .perform(get("/api/v1/notes/2026-05-07"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.date").value("2026-05-07"))
            .andExpect(jsonPath("$.note").value("테스트 메모"))
    }

    @Test
    fun `PUT _api_v1_notes_date upsert 200`() {
        val date = LocalDate.parse("2026-05-07")
        whenever(dayNoteRepository.findByUserIdAndNoteDate(eq(TEST_USER_ID), eq(date)))
            .thenReturn(Optional.empty())
        whenever(userRepository.findById(eq(TEST_USER_ID))).thenReturn(Optional.of(stubUser()))
        whenever(dayNoteRepository.save(any<DayNote>())).thenAnswer { it.getArgument<DayNote>(0) }

        mockMvc
            .perform(
                put("/api/v1/notes/2026-05-07")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SaveNoteRequest(note = "오늘 메모"))),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.note").value("오늘 메모"))
    }

    @Test
    fun `DELETE _api_v1_notes_date 200`() {
        mockMvc
            .perform(delete("/api/v1/notes/2026-05-07"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.message").exists())
    }

    // ── Negative cases ────────────────────────────────────────────────────────────

    @Test
    fun `GET _api_v1_notes_date 404 when note not found`() {
        val date = LocalDate.parse("2026-05-08")
        whenever(dayNoteRepository.findByUserIdAndNoteDate(eq(TEST_USER_ID), eq(date)))
            .thenReturn(Optional.empty())

        mockMvc
            .perform(get("/api/v1/notes/2026-05-08"))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"))
    }

    @Test
    fun `PUT _api_v1_notes_date 404 when user not found`() {
        // 신규 메모 작성 경로에서 user 없음 → USER_NOT_FOUND. (기존 메모가 있으면
        // userRepository 조회를 건너뛰므로 신규 작성 흐름만 검증.)
        val date = LocalDate.parse("2026-05-08")
        whenever(dayNoteRepository.findByUserIdAndNoteDate(eq(TEST_USER_ID), eq(date)))
            .thenReturn(Optional.empty())
        whenever(userRepository.findById(eq(TEST_USER_ID))).thenReturn(Optional.empty())

        mockMvc
            .perform(
                put("/api/v1/notes/2026-05-08")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(SaveNoteRequest(note = "오늘 메모"))),
            ).andExpect(status().isNotFound)
            .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"))
    }

    private fun stubUser() =
        User(
            id = TEST_USER_ID,
            nickname = "tester",
            role = UserRole.USER,
        )
}
