package com.terraworld.domain.terrarium

import org.springframework.data.jpa.repository.EntityGraph
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface TerrariumRepository : JpaRepository<Terrarium, Long> {
    fun findByUserId(userId: String): Optional<Terrarium>

    /**
     * WiltScheduler 배치용 projection — 전체 terrarium 을 스캔하되 userId + wiltRecoveredAt 만 필요.
     * findAll() + per-row lazy `terrarium.user.id` 접근(N+1, User 프록시 로드)을 회피.
     */
    @Query("SELECT t.user.id AS userId, t.wiltRecoveredAt AS wiltRecoveredAt FROM Terrarium t")
    fun findAllWiltScanProjections(): List<WiltScanProjection>

    /**
     * M5: 조건부 원자 티어 상승(CAS). read→검증→set tier(무락) 경로의 double-debit 창을 제거한다.
     * WHERE t.tier = :current 로 관측한 상태에서만 상승 → 동시 요청 중 하나만 rows==1, 나머지는 rows==0.
     * 호출부(unlockTier)는 debit 후 실행하여 rows==0 이면 BusinessException throw → @Transactional 이 debit 롤백.
     * clearAutomatically: 벌크 UPDATE 후 영속성 컨텍스트의 stale Terrarium(tier) 를 무효화해 후속 조회 정합 보장.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE Terrarium t SET t.tier = :target, t.updatedAt = :updatedAt WHERE t.id = :id AND t.tier = :current")
    fun casTier(
        @Param("id") id: Long,
        @Param("current") current: String,
        @Param("target") target: String,
        @Param("updatedAt") updatedAt: LocalDateTime,
    ): Int
}

/** WiltScheduler 배치 스캔용 read-only projection (userId + wiltRecoveredAt). */
interface WiltScanProjection {
    val userId: String
    val wiltRecoveredAt: LocalDateTime?
}

interface TerrariumBackgroundRepository : JpaRepository<TerrariumBackground, Long> {
    /**
     * 배경 변경(setBackground)의 find-or-create 매칭 키 — 아이템 파생 배경은 asset_url 로 재사용 판정.
     * 유니크 제약이 없어 동시 생성 시 중복 행이 있을 수 있으므로 최소 id 로 결정적 선택.
     */
    fun findFirstByAssetUrlOrderByIdAsc(assetUrl: String): TerrariumBackground?
}

interface TerrariumPlacementRepository : JpaRepository<TerrariumPlacement, Long> {
    fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement>

    /** 아프젝 v2: 특정 병(티어)의 배치 — 배치 재저장(updatePlacements) 의 보존/교체 대상. */
    @EntityGraph(attributePaths = ["item"])
    fun findAllByTerrariumIdAndTierOrderById(
        terrariumId: Long,
        tier: String,
    ): List<TerrariumPlacement>

    /**
     * 아프젝 v2: 표시 중 병(terrariums.active_tier) 스코프의 배치 — 홈/자유배치/친구 방문/공유 응답 조립.
     * item fetch join 으로 N+1 회피 (toFreeItem / PlacedItemDetail 이 item 을 읽음). id 정렬로 응답 순서 결정성
     * (frontend 가 index 로 z-index 부여하므로 안정 정렬 필요).
     */
    @Query(
        """
        SELECT p FROM TerrariumPlacement p JOIN FETCH p.item
        WHERE p.terrarium.user.id = :userId AND p.tier = p.terrarium.activeTier
        ORDER BY p.id
        """,
    )
    fun findActiveTierPlacementsByUserId(
        @Param("userId") userId: String,
    ): List<TerrariumPlacement>

    fun deleteAllByTerrariumIdAndTier(
        terrariumId: Long,
        tier: String,
    )
}
