package com.terraworld.domain.growth

import com.terraworld.common.cache.CacheNames
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository

interface GrowthStageRepository : JpaRepository<GrowthStage, GrowthStageId> {
    /**
     * BE-08: kind 별 육성 stage 임계값 config (GrowthService — 종마다 재조회하던 hot path).
     * V27 seed 정적 config — Caffeine TTL 10분 캐시 (stage 조정 시 최대 10분 stale 수용).
     * ⚠️ 캐시 hit 시 detached 엔티티 공유 — 전 필드 val + 연관 없음, read-only 사용 전용 (수정/save 금지).
     */
    @Cacheable(CacheNames.GROWTH_STAGES)
    fun findAllByKindOrderByStageOrderAsc(kind: String): List<GrowthStage>
}
