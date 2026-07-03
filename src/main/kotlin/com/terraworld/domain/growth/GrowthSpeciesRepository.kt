package com.terraworld.domain.growth

import org.springframework.data.jpa.repository.JpaRepository

interface GrowthSpeciesRepository : JpaRepository<GrowthSpecies, String> {
    fun findAllByOrderBySortOrderAsc(): List<GrowthSpecies>
}
