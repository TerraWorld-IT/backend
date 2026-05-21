package com.terraworld.domain.reward

import com.terraworld.common.time.KstTime
import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * /analyze 2026-05-18 (Codex F6): RewardService 의 Redis 키 / DB count 가 모두
 * KST date 기반인데, `watchedAt` 기본값이 JVM default timezone (Docker = UTC)
 * 의존이라 자정 ±9h 윈도우에서 일일 5회 제한 오작동 가능.
 *
 * Fix: `KstTime.now()` (= `LocalDateTime.now(ZoneId.of("Asia/Seoul"))`) 로 KST
 * wallclock 명시. DB column 은 TIMESTAMP (timezone-naive) — 모든 row 가 KST
 * wallclock 으로 저장된다는 컨벤션을 본 entity + RewardService 가 함께 강제.
 */
@Entity
@Table(name = "ad_watch_logs")
class AdWatchLog(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "watched_at", nullable = false, updatable = false)
    val watchedAt: LocalDateTime = KstTime.now(),
    @Column(name = "reward_special_coins", nullable = false)
    val rewardSpecialCoins: Int = 1,
)
