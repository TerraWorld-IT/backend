package com.terraworld.domain.character

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * 캐릭터(정령) config (낙서장 리팩토링 P0b). V26 seed 로스터.
 * 정책 #5: 판매 X, 획득경로 = DEFAULT/GROW(키우기)/TIER(테라리움 티어지급)/EVENT.
 */
@Entity
@Table(name = "character_defs")
class CharacterDef(
    @Id
    @Column(length = 32)
    val code: String = "",
    @Column(name = "name_ko", nullable = false, length = 32)
    val nameKo: String = "",
    @Column(nullable = false, length = 16)
    val habitat: String = "", // 서식지 (LAND=육지 | AQUATIC=수생)
    @Column(nullable = false)
    val sellable: Boolean = false,
    @Column(name = "acquire_source", nullable = false, length = 16)
    val acquireSource: String = "",
    @Column(name = "sort_order", nullable = false)
    val sortOrder: Int = 0,
)
