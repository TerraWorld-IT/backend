package com.terraworld.domain.note

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.util.Optional

interface DayNoteRepository : JpaRepository<DayNote, Long> {
    fun findByUserIdAndNoteDate(userId: Long, noteDate: LocalDate): Optional<DayNote>
    fun findAllByUserIdAndNoteDateBetween(userId: Long, from: LocalDate, to: LocalDate): List<DayNote>
    fun deleteByUserIdAndNoteDate(userId: Long, noteDate: LocalDate)
}
