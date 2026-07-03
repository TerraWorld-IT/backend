package com.terraworld.domain.growth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Table
import java.io.Serializable

/** 육성 stage 임계값 config (kind × stage_order). 낙서장 1/15/30 seed, 확장 가능. */
@Entity
@Table(name = "growth_stages")
@IdClass(GrowthStageId::class)
class GrowthStage(
    @Id
    @Column(length = 16)
    val kind: String = "",
    @Id
    @Column(name = "stage_order")
    val stageOrder: Int = 0,
    @Column(nullable = false)
    val threshold: Int = 0,
    @Column(name = "label_ko", nullable = false, length = 24)
    val labelKo: String = "",
)

data class GrowthStageId(
    val kind: String = "",
    val stageOrder: Int = 0,
) : Serializable
