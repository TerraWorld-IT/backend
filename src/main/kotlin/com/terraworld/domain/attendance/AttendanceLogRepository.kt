package com.terraworld.domain.attendance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.util.Optional

interface AttendanceLogRepository : JpaRepository<AttendanceLog, Long> {
    fun findByUserIdAndCheckInDate(
        userId: String,
        checkInDate: LocalDate,
    ): Optional<AttendanceLog>

    /**
     * 가장 최근 출석 로그 (오늘 미수령 시에도 어제까지의 streak 정보 보존).
     */
    @Query(
        "SELECT a FROM AttendanceLog a WHERE a.userId = :userId ORDER BY a.checkInDate DESC LIMIT 1",
    )
    fun findLatestByUserId(
        @Param("userId") userId: String,
    ): Optional<AttendanceLog>

    @Query("SELECT COALESCE(MAX(a.streak), 0) FROM AttendanceLog a WHERE a.userId = :userId")
    fun findLongestStreak(
        @Param("userId") userId: String,
    ): Int

    /** 아프젝 v2: 이번 7일 사이클 구간(1일차 ~ 마지막 출석일)의 로그 — 보드 claimed/claimedAt 파생. */
    fun findAllByUserIdAndCheckInDateBetween(
        userId: String,
        start: LocalDate,
        end: LocalDate,
    ): List<AttendanceLog>
}
