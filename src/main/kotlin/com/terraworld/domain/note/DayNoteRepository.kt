package com.terraworld.domain.note

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.Optional

interface DayNoteRepository : JpaRepository<DayNote, Long> {
    fun findByUserIdAndNoteDate(userId: String, noteDate: LocalDate): Optional<DayNote>
    fun findAllByUserIdAndNoteDateBetween(userId: String, from: LocalDate, to: LocalDate): List<DayNote>
    fun deleteByUserIdAndNoteDate(userId: String, noteDate: LocalDate)
}
