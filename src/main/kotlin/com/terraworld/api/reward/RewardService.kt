package com.terraworld.api.reward

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.user.UserRepository
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@Service
class RewardService(
    private val adWatchLogRepository: AdWatchLogRepository,
    private val userRepository: UserRepository,
    private val redisTemplate: StringRedisTemplate,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
) {
    companion object {
        const val DAILY_AD_LIMIT = 5
        const val REWARD_SPECIAL_COINS = 1

        // 앱 namespace prefix — 같은 Redis 인스턴스를 다른 시스템과 공유 시 키 충돌 방지.
        private const val REDIS_DAILY_KEY_PREFIX = "terraworld:reward:ad:daily:"
    }

    @Transactional
    fun claimAdReward(userId: String): AdRewardResponsePayload {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val today = LocalDate.now()

        // 1) Redis 빠른 게이트 — INCR + 25h TTL. 어뷰징 burst 차단.
        //    Redis 장애 시 아래 DB 검증으로 폴백 (graceful degradation).
        val redisKey = "$REDIS_DAILY_KEY_PREFIX$userId:$today"
        try {
            val redisCount = redisTemplate.opsForValue().increment(redisKey) ?: 0L
            if (redisCount == 1L) {
                redisTemplate.expire(redisKey, Duration.ofHours(25))
            }
            if (redisCount > DAILY_AD_LIMIT) {
                throw BusinessException(ErrorCode.AD_DAILY_LIMIT_EXCEEDED)
            }
        } catch (_: DataAccessException) {
            // Redis 장애 — DB 검증으로 폴백
        }

        // 2) DB 검증 — source of truth. Redis 누락분 보정.
        val dayStart = today.atStartOfDay()
        val dayEnd = today.atTime(LocalTime.MAX)
        val watchedToday = adWatchLogRepository.countByUserIdAndWatchedAtBetween(userId, dayStart, dayEnd)

        if (watchedToday >= DAILY_AD_LIMIT) {
            throw BusinessException(ErrorCode.AD_DAILY_LIMIT_EXCEEDED)
        }

        user.specialCoin += REWARD_SPECIAL_COINS
        userRepository.save(user)
        adWatchLogRepository.save(
            AdWatchLog(userId = userId, rewardSpecialCoins = REWARD_SPECIAL_COINS),
        )

        val newCount = (watchedToday + 1).toInt()
        auditService.publish(
            userId = userId,
            action = "REWARD_AD_CLAIMED",
            resourceType = "Reward",
            payload =
                mapOf(
                    "specialCoins" to REWARD_SPECIAL_COINS,
                    "dailyWatchCount" to newCount,
                ),
        )
        return AdRewardResponsePayload(
            reward = AdRewardPayload(specialCoins = REWARD_SPECIAL_COINS),
            dailyWatchCount = newCount,
            remainingToday = DAILY_AD_LIMIT - newCount,
            updatedCurrency = currencyBuilder.build(userId, user),
        )
    }
}
