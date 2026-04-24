package com.terraworld.domain.reward

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

interface AdWatchLogRepository : JpaRepository<AdWatchLog, Long> {
    fun countByUserIdAndWatchedAtBetween(
        userId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): Long
}
