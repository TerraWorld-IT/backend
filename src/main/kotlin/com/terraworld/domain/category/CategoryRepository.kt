package com.terraworld.domain.category

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, Long> {
    /**
     * 활성 카테고리 목록. 시스템 카테고리(isCustom=false) 만 반환.
     * 다른 사용자의 커스텀 카테고리는 노출되지 않게 위해 필터링한다.
     */
    fun findAllByIsActiveTrueAndIsCustomFalse(): List<Category>

    /**
     * 본인 커스텀 카테고리 목록.
     */
    fun findAllByOwnerUserIdAndIsActiveTrue(ownerUserId: String): List<Category>

    fun countByOwnerUserIdAndIsActiveTrue(ownerUserId: String): Long

    fun existsByNameAndOwnerUserIdAndIsActiveTrue(
        name: String,
        ownerUserId: String,
    ): Boolean

    fun existsByNameAndIsCustomFalse(name: String): Boolean

    @Deprecated("isCustom-aware 버전 사용 권장", ReplaceWith("findAllByIsActiveTrueAndIsCustomFalse()"))
    fun findAllByIsActiveTrue(): List<Category>
}
