package com.terraworld.domain.terrarium

import com.terraworld.common.cache.CacheNames
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 테라리움 티어 카탈로그 (낙서장 P2). 해금 비용(반짝이/루비) + 슬롯 수 + 티어 보상 정령.
 * tier_configs 테이블 SoT — 코드/enum 하드코딩 대신 DB seed 참조(admin 조정 여지).
 * 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md
 */
@Entity
@Table(name = "tier_configs")
class TierConfig(
    @Id
    @Column(name = "tier", length = 32)
    val tier: String = "",
    @Column(name = "tier_order", nullable = false)
    val tierOrder: Int = 0,
    @Column(name = "name_ko", nullable = false, length = 32)
    val nameKo: String = "",
    @Column(name = "sparkle_cost", nullable = false)
    val sparkleCost: Long = 0,
    @Column(name = "ruby_cost", nullable = false)
    val rubyCost: Long = 0,
    @Column(name = "slots", nullable = false)
    val slots: Int = 0,
    @Column(name = "spirit_code", length = 32)
    val spiritCode: String? = null,
    // 아프젝 v2 (V39): 표시용 레벨(= tier_order) / 소개 문구 / 활성 여부 / 미리보기 에셋
    @Column(name = "level", nullable = false)
    val level: Int = tierOrder,
    @Column(name = "description_ko", nullable = false, length = 100)
    val descriptionKo: String = "",
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @Column(name = "preview_asset_url", length = 500)
    val previewAssetUrl: String? = null,
)

interface TierConfigRepository : JpaRepository<TierConfig, String> {
    fun findAllByOrderByTierOrderAsc(): List<TierConfig>

    /** 아프젝 v2: 카탈로그/해금/표시 지정은 활성 티어만 대상 (HOUSE_TANK 비활성). */
    fun findAllByIsActiveTrueOrderByTierOrderAsc(): List<TierConfig>

    /**
     * BE-08: tier → slots 조회 (TerrariumService getTerrarium/updatePlacements hot path).
     * 티어 config 는 4행 seed — Caffeine TTL 10분 캐시(admin slots 변경 시 최대 10분 stale 수용).
     * Int scalar projection 이라 detached entity 문제 없음. 미존재 tier 는 null (호출부 `?: 6` fallback
     * — 기존 findById().map{slots}.orElse(6) semantics 동일).
     * TierService(해금 mutation 경로)는 비용 검증에 fresh findById 를 유지 — 캐시 비대상.
     */
    @Cacheable(CacheNames.TIER_SLOTS)
    @Query("SELECT t.slots FROM TierConfig t WHERE t.tier = :tier")
    fun findSlotsByTier(
        @Param("tier") tier: String,
    ): Int?
}
