package com.terraworld.domain.reward

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface AdWatchLogRepository : JpaRepository<AdWatchLog, Long> {
    fun countByUserIdAndWatchedAtBetween(
        userId: String,
        from: LocalDateTime,
        to: LocalDateTime,
    ): Long

    /**
     * R10 AD-REWARD-REDIS-FALLBACK-RACE: Redis 장애 시 DB fallback 이 유일 cap 가드가 되는데 count-then-insert
     * 무락이라 동시 요청(서로 다른 nonce)이 같은 count 를 보고 일일 cap 을 초과 mint 할 수 있다. per-(userId|date)
     * tx-scoped advisory lock 으로 count+insert 를 직렬화. (RecordRepository.acquireRecordDailyLock 패턴.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireAdRewardDailyLock(
        @Param("key") key: String,
    ): Int
}
