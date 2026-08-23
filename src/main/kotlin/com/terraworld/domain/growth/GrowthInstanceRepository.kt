package com.terraworld.domain.growth

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.UUID

interface GrowthInstanceRepository : JpaRepository<GrowthInstance, Long> {
    fun findAllByUserId(userId: String): List<GrowthInstance>

    fun findByUserIdAndSpeciesCode(
        userId: String,
        speciesCode: String,
    ): GrowthInstance?

    /**
     * 아프젝 v2: ACTIVE → LOST 조건부 전환(CAS). 조회 경로(getGrowth)와 mutation 경로가 동시에 판정해도
     * 한쪽만 rows==1 — 엔티티 save 방식이면 GET 끼리 @Version 충돌(409)이 날 수 있어 bulk UPDATE 로 처리한다.
     * version 도 함께 올려 같은 tx 밖의 stale 엔티티 저장을 낙관락이 잡게 한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE GrowthInstance g SET g.cycleState = com.terraworld.domain.growth.GrowthCycleState.LOST, " +
            "g.lostAt = :now, g.version = g.version + 1 " +
            "WHERE g.id = :id AND g.cycleState = com.terraworld.domain.growth.GrowthCycleState.ACTIVE",
    )
    fun markLost(
        @Param("id") id: Long,
        @Param("now") now: LocalDateTime,
    ): Int

    /**
     * 아프젝 v2: 완료 사이클 리셋(CAS) — `reset_processed_at IS NULL` 인 COMPLETED 개체를 기한 도래 시 새 사이클
     * (ACTIVE, 진행 0, 새 cycle_id, notifyNext 해제)로 1회만 전환. 스케줄러(KST 00:05)와 조회 시 이중 경로가
     * 같은 문장을 쓰므로 알림(SPIRIT_ARRIVED)은 rows==1 을 받은 쪽만 발행한다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE GrowthInstance g SET g.cycleState = com.terraworld.domain.growth.GrowthCycleState.ACTIVE, " +
            "g.cycleId = :newCycleId, g.naturalStreak = 0, g.sparkleBoughtCount = 0, g.currentStage = 1, " +
            "g.lastProgressAt = NULL, g.completedKstDate = NULL, g.completedAt = NULL, g.resetDueKstDate = NULL, " +
            "g.resetProcessedAt = :now, g.notifyNext = false, g.reviveSnoozedUntilKstDate = NULL, g.lostAt = NULL, " +
            "g.version = g.version + 1 " +
            "WHERE g.id = :id AND g.cycleState = com.terraworld.domain.growth.GrowthCycleState.COMPLETED " +
            "AND g.resetProcessedAt IS NULL AND g.resetDueKstDate IS NOT NULL AND g.resetDueKstDate <= :today",
    )
    fun resetCompletedCycle(
        @Param("id") id: Long,
        @Param("today") today: LocalDate,
        @Param("now") now: LocalDateTime,
        @Param("newCycleId") newCycleId: UUID,
    ): Int

    /**
     * 아프젝 v2: 되살리기 보류(revive-dismiss) 만료 리셋(CAS) — LOST 이고 `revive_snoozed_until <= today` 인 개체를
     * 새 사이클(ACTIVE, 진행 0, 정령 초기화)로 1회만 전환. 알림 없음.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        "UPDATE GrowthInstance g SET g.cycleState = com.terraworld.domain.growth.GrowthCycleState.ACTIVE, " +
            "g.cycleId = :newCycleId, g.naturalStreak = 0, g.sparkleBoughtCount = 0, g.currentStage = 1, " +
            "g.lastProgressAt = NULL, g.completedKstDate = NULL, g.completedAt = NULL, g.resetDueKstDate = NULL, " +
            "g.resetProcessedAt = :now, g.reviveSnoozedUntilKstDate = NULL, g.lostAt = NULL, " +
            "g.version = g.version + 1 " +
            "WHERE g.id = :id AND g.cycleState = com.terraworld.domain.growth.GrowthCycleState.LOST " +
            "AND g.reviveSnoozedUntilKstDate IS NOT NULL AND g.reviveSnoozedUntilKstDate <= :today",
    )
    fun resetSnoozedCycle(
        @Param("id") id: Long,
        @Param("today") today: LocalDate,
        @Param("now") now: LocalDateTime,
        @Param("newCycleId") newCycleId: UUID,
    ): Int

    /** 스케줄러 스캔: 리셋 기한이 도래한 완료 개체 id. */
    @Query(
        "SELECT g.id FROM GrowthInstance g WHERE g.cycleState = com.terraworld.domain.growth.GrowthCycleState.COMPLETED " +
            "AND g.resetProcessedAt IS NULL AND g.resetDueKstDate IS NOT NULL AND g.resetDueKstDate <= :today",
    )
    fun findCompletedResetDueIds(
        @Param("today") today: LocalDate,
    ): List<Long>

    /** 스케줄러 스캔: 되살리기 보류가 만료된 LOST 개체 id. */
    @Query(
        "SELECT g.id FROM GrowthInstance g WHERE g.cycleState = com.terraworld.domain.growth.GrowthCycleState.LOST " +
            "AND g.reviveSnoozedUntilKstDate IS NOT NULL AND g.reviveSnoozedUntilKstDate <= :today",
    )
    fun findSnoozeExpiredIds(
        @Param("today") today: LocalDate,
    ): List<Long>
}
