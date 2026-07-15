package com.terraworld.domain.category

import com.terraworld.common.cache.CacheNames
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, Long> {
    /**
     * 활성 카테고리 목록. 시스템 카테고리(isCustom=false) 만 반환.
     * 다른 사용자의 커스텀 카테고리는 노출되지 않게 위해 필터링한다.
     *
     * BE-08: 시스템 카테고리는 4행 seed 정적 config (CategoryController 가 커스텀 생성/삭제를
     * isCustom=true 로만 허용) — Caffeine TTL 10분 캐시. admin 직접 변경 시 최대 10분 stale 수용.
     * ⚠️ 캐시 hit 시 detached 엔티티 공유 — read-only 사용 전용 (수정/save 금지). 기록 생성 등
     * write 경로는 findById 유지 (캐시 비대상).
     */
    @Cacheable(CacheNames.SYSTEM_CATEGORIES)
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
}
