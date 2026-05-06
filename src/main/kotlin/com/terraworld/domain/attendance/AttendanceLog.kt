package com.terraworld.domain.attendance

import jakarta.persistence.*
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table(name = "attendance_logs", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "check_in_date"])])
class AttendanceLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String,
    @Column(name = "check_in_date", nullable = false)
    val checkInDate: LocalDate,
    @Column(nullable = false)
    val streak: Int,
    @Column(name = "reward_basic_coins", nullable = false)
    val rewardBasicCoins: Int,
    @Column(nullable = false)
    val bonus: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
