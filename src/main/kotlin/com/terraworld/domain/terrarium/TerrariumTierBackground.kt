package com.terraworld.domain.terrarium

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * 티어(병)별 배경 (아프젝 v2 §R1 후속, V40). 배경은 배치와 같이 active_tier 스코프 —
 * 병마다 배경을 따로 가지며 `(terrarium_id, tier)` 유니크. 행이 없는 병은 `terrariums.background_id`
 * (V40 이전 값 / 기본 배경) 로 폴백한다. setBackground 는 표시 중 병의 행만 upsert 한다.
 */
@Entity
@Table(name = "terrarium_tier_backgrounds")
class TerrariumTierBackground(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "terrarium_id", nullable = false)
    val terrariumId: Long = 0,
    @Column(name = "tier", nullable = false, length = 32)
    val tier: String = "GLASS_JAR",
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "background_id", nullable = false)
    var background: TerrariumBackground,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
)

interface TerrariumTierBackgroundRepository : JpaRepository<TerrariumTierBackground, Long> {
    /** 특정 병의 배경 행 (배경 fetch) — 없으면 null → 호출부가 terrariums.background 로 폴백. */
    @EntityGraph(attributePaths = ["background"])
    fun findByTerrariumIdAndTier(
        terrariumId: Long,
        tier: String,
    ): TerrariumTierBackground?

    /**
     * 표시 중 병의 배경 upsert — `(terrarium_id, tier)` 유니크에 ON CONFLICT 로 원자 갱신.
     * find-or-create(read→insert) 는 같은 병의 동시 최초 설정에서 유니크 위반(500)이 나므로 native upsert 로 처리한다.
     * clearAutomatically: 같은 tx 에서 이전에 로드한 행이 있으면 stale 이므로 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = """
            INSERT INTO terrarium_tier_backgrounds (terrarium_id, tier, background_id, updated_at)
            VALUES (:terrariumId, :tier, :backgroundId, :updatedAt)
            ON CONFLICT (terrarium_id, tier)
            DO UPDATE SET background_id = EXCLUDED.background_id, updated_at = EXCLUDED.updated_at
            """,
        nativeQuery = true,
    )
    fun upsert(
        @Param("terrariumId") terrariumId: Long,
        @Param("tier") tier: String,
        @Param("backgroundId") backgroundId: Long,
        @Param("updatedAt") updatedAt: LocalDateTime,
    ): Int
}
