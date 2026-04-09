package com.terraworld.domain.level

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface LevelConfigRepository : JpaRepository<LevelConfig, Int> {
    fun findByLevel(level: Int): Optional<LevelConfig>
}
