package com.terraworld.api.note

import com.terraworld.api.note.dto.NoteResponse
import com.terraworld.api.note.dto.SaveNoteRequest
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.note.DayNote
import com.terraworld.domain.note.DayNoteRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.LocalDate
import java.time.LocalDateTime

@Tag(name = "Note", description = "캘린더 메모 API")
@RestController
@RequestMapping("/api/v1/notes")
class NoteController(
    private val dayNoteRepository: DayNoteRepository,
    private val userRepository: UserRepository,
) {
    @Operation(summary = "특정 날짜 메모 조회")
    @GetMapping("/{date}")
    fun getNote(
        @PathVariable date: String,
    ): NoteResponse {
        val noteDate = LocalDate.parse(date)
        val userId = SecurityUtil.getCurrentUserId()
        val note =
            dayNoteRepository
                .findByUserIdAndNoteDate(userId, noteDate)
                .orElseThrow { BusinessException(ErrorCode.NOTE_NOT_FOUND) }
        return NoteResponse(date = note.noteDate.toString(), note = note.note)
    }

    @Operation(summary = "메모 저장/수정 (upsert)")
    @PutMapping("/{date}")
    @Transactional
    fun saveNote(
        @PathVariable date: String,
        @Valid @RequestBody request: SaveNoteRequest,
    ): NoteResponse {
        val noteDate = LocalDate.parse(date)
        val userId = SecurityUtil.getCurrentUserId()

        val existing = dayNoteRepository.findByUserIdAndNoteDate(userId, noteDate)
        if (existing.isPresent) {
            val note = existing.get()
            note.note = request.note
            note.updatedAt = LocalDateTime.now()
            dayNoteRepository.save(note)
            return NoteResponse(date = note.noteDate.toString(), note = note.note)
        }

        val user = userRepository.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val note = dayNoteRepository.save(DayNote(user = user, noteDate = noteDate, note = request.note))
        return NoteResponse(date = note.noteDate.toString(), note = note.note)
    }

    @Operation(summary = "메모 삭제")
    @DeleteMapping("/{date}")
    @Transactional
    fun deleteNote(
        @PathVariable date: String,
    ): Map<String, String> {
        val noteDate = LocalDate.parse(date)
        dayNoteRepository.deleteByUserIdAndNoteDate(SecurityUtil.getCurrentUserId(), noteDate)
        return mapOf("message" to "메모가 삭제되었습니다")
    }
}
