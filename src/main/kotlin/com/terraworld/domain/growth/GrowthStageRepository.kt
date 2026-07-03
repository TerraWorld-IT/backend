package com.terraworld.domain.growth

import org.springframework.data.jpa.repository.JpaRepository

interface GrowthStageRepository : JpaRepository<GrowthStage, GrowthStageId> {
    fun findAllByKindOrderByStageOrderAsc(kind: String): List<GrowthStage>
}
