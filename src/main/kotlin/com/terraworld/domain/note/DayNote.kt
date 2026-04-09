package com.terraworld.domain.note

import com.terraworld.domain.user.User
import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "day_notes", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "note_date"])])
class DayNote(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,

    @Column(name = "note_date", nullable = false)
    val noteDate: LocalDate,

    @Column(nullable = false, columnDefinition = "TEXT")
    var note: String,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)
