package com.terraworld.domain.growth

import com.terraworld.common.cache.CacheNames
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface GrowthSpeciesRepository : JpaRepository<GrowthSpecies, String> {
    /**
     * BE-08: 육성 종 config 목록 (GrowthService — getGrowth 매 조회 + 기록마다 advanceAllStreaks).
     * V27 seed 정적 config — Caffeine TTL 10분 캐시 (종 추가 시 최대 10분 stale 수용).
     * ⚠️ 캐시 hit 시 detached 엔티티 공유 — 전 필드 val + 연관 없음, read-only 사용 전용 (수정/save 금지).
     */
    @Cacheable(CacheNames.GROWTH_SPECIES)
    fun findAllByOrderBySortOrderAsc(): List<GrowthSpecies>
}
