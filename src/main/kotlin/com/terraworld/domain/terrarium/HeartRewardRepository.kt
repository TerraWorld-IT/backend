package com.terraworld.domain.terrarium

import com.terraworld.domain.wallet.WalletTransaction
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.Repository
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/** 기존 지갑 원장에서 KST 일자별 하트 코인 지급 횟수를 조회한다. */
interface HeartRewardRepository : Repository<WalletTransaction, Long> {
    @Query(
        """
        SELECT COUNT(tx)
        FROM WalletTransaction tx
        WHERE tx.user.id = :userId
          AND tx.currencyCode = 'COIN'
          AND tx.reason = 'RECORD_REWARD'
          AND tx.refType = 'HEART'
          AND tx.amount > 0
          AND tx.createdAt >= :dayStartUtc
          AND tx.createdAt < :nextDayStartUtc
        """,
    )
    fun countGrantedRewards(
        @Param("userId") userId: String,
        @Param("dayStartUtc") dayStartUtc: LocalDateTime,
        @Param("nextDayStartUtc") nextDayStartUtc: LocalDateTime,
    ): Long

    /** 같은 사용자의 동일 KST 날짜 count + 지급을 트랜잭션 종료까지 직렬화한다. */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireDailyLock(
        @Param("key") key: String,
    ): Int
}
