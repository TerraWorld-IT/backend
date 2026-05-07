package com.terraworld.api.note

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.note.DayNote
import com.terraworld.domain.note.DayNoteRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.NoteApi
import io.terraworld.api.model.MessageResponse
import io.terraworld.api.model.NoteResponse
import io.terraworld.api.model.SaveNoteRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * NoteApi (3 endpoints) 직접 implement. local NoteDtos 는 generated 와
 * 동등하므로 NoteDtos.kt 를 제거하고 generated DTO 만 사용.
 */
@Tag(name = "Note", description = "캘린더 메모 API")
@RestController
@RequestMapping("/api/v1")
class NoteController(
    private val dayNoteRepository: DayNoteRepository,
    private val userRepository: UserRepository,
) : NoteApi {
    @Operation(summary = "특정 날짜 메모 조회")
    override fun getNote(date: LocalDate): ResponseEntity<NoteResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val note =
            dayNoteRepository
                .findByUserIdAndNoteDate(userId, date)
                .orElseThrow { BusinessException(ErrorCode.NOTE_NOT_FOUND) }
        return ResponseEntity.ok(NoteResponse(date = note.noteDate, note = note.note))
    }

    @Operation(summary = "메모 저장/수정 (upsert)")
    @Transactional
    override fun saveNote(
        date: LocalDate,
        @Valid saveNoteRequest: SaveNoteRequest,
    ): ResponseEntity<NoteResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val existing = dayNoteRepository.findByUserIdAndNoteDate(userId, date)
        if (existing.isPresent) {
            val note = existing.get()
            note.note = saveNoteRequest.note
            note.updatedAt = LocalDateTime.now()
            dayNoteRepository.save(note)
            return ResponseEntity.ok(NoteResponse(date = note.noteDate, note = note.note))
        }

        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val note = dayNoteRepository.save(DayNote(user = user, noteDate = date, note = saveNoteRequest.note))
        return ResponseEntity.ok(NoteResponse(date = note.noteDate, note = note.note))
    }

    @Operation(summary = "메모 삭제")
    @Transactional
    override fun deleteNote(date: LocalDate): ResponseEntity<MessageResponse> {
        dayNoteRepository.deleteByUserIdAndNoteDate(SecurityUtil.getCurrentUserId(), date)
        return ResponseEntity.ok(MessageResponse(message = "메모가 삭제되었습니다"))
    }
}
