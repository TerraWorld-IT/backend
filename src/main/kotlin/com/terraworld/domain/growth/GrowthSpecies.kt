package com.terraworld.domain.growth

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/** 육성 종 config (낙서장 P3). kind=SPIRIT|PLANT. V27 seed(고양이/토마토덩굴), +@ = row 추가. */
@Entity
@Table(name = "growth_species")
class GrowthSpecies(
    @Id
    @Column(length = 32)
    val code: String = "",
    @Column(nullable = false, length = 16)
    val kind: String = "",
    @Column(name = "name_ko", nullable = false, length = 32)
    val nameKo: String = "",
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,
)
