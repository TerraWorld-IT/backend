package com.terraworld.domain.growth

import org.springframework.data.jpa.repository.JpaRepository

interface GrowthInstanceRepository : JpaRepository<GrowthInstance, Long> {
    fun findAllByUserId(userId: String): List<GrowthInstance>

    fun findByUserIdAndSpeciesCode(
        userId: String,
        speciesCode: String,
    ): GrowthInstance?
}
