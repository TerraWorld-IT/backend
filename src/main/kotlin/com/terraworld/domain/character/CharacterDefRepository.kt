package com.terraworld.domain.character

import org.springframework.data.jpa.repository.JpaRepository

interface CharacterDefRepository : JpaRepository<CharacterDef, String> {
    fun findAllByOrderBySortOrderAsc(): List<CharacterDef>
}
