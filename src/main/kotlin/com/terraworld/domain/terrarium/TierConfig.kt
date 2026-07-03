package com.terraworld.domain.terrarium

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository

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
)

interface TierConfigRepository : JpaRepository<TierConfig, String> {
    fun findAllByOrderByTierOrderAsc(): List<TierConfig>
}
