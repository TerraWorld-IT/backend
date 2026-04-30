package com.terraworld.domain.terrarium

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface TerrariumRepository : JpaRepository<Terrarium, Long> {
    fun findByUserId(userId: String): Optional<Terrarium>
}

interface TerrariumBackgroundRepository : JpaRepository<TerrariumBackground, Long>

interface TerrariumPlacementRepository : JpaRepository<TerrariumPlacement, Long> {
    fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement>

    fun deleteAllByTerrariumId(terrariumId: Long)
}
