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

interface TerrariumBackgroundRepository : JpaRepository<TerrariumBackground, Long>

interface TerrariumPlacementRepository : JpaRepository<TerrariumPlacement, Long> {
    fun findAllByTerrariumId(terrariumId: Long): List<TerrariumPlacement>

    // 자유배치 로드용 (free.vue) — terrarium.user.id 로 현재 사용자의 모든 배치 조회.
    // @EntityGraph(item): toFreeItem() 이 item 을 읽으므로 N+1 회피. OrderById: 응답 순서 결정성
    // (frontend 가 index 로 z-index 부여하므로 안정 정렬 필요).
    @EntityGraph(attributePaths = ["item"])
    fun findAllByTerrariumUserIdOrderById(userId: String): List<TerrariumPlacement>

    fun deleteAllByTerrariumId(terrariumId: Long)
}
