package com.terraworld.api.reward

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.AdRewardResponse
import io.terraworld.api.model.AdRewardResponseReward
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.LocalTime

@Service
class RewardService(
    private val adWatchLogRepository: AdWatchLogRepository,
    private val userRepository: UserRepository,
    private val terrariumRepository: TerrariumRepository,
    private val redisTemplate: StringRedisTemplate,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 광고 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
) {
    companion object {
        const val DAILY_AD_LIMIT = 5
        const val REWARD_SPECIAL_COINS = 1

        // 앱 namespace prefix — 같은 Redis 인스턴스를 다른 시스템과 공유 시 키 충돌 방지.
        private const val REDIS_DAILY_KEY_PREFIX = "terraworld:reward:ad:daily:"
    }

    /**
     * ARCH-008-phase-5: 반환 타입을 generated [AdRewardResponse] 로 직접. local
     * `AdRewardResponsePayload` / `AdRewardPayload` 삭제. controller mapper 제거.
     */
    @Transactional
    fun claimAdReward(userId: String): AdRewardResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val today = KstTime.today()

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
        val adLog =
            adWatchLogRepository.save(
                AdWatchLog(userId = userId, rewardSpecialCoins = REWARD_SPECIAL_COINS),
            )

        // N2: wallet ledger — SPECIAL_COIN row
        walletTransactionService.append(
            user = user,
            currencyType = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_SPECIAL_COIN,
            amount = REWARD_SPECIAL_COINS.toLong(),
            balanceAfter = user.specialCoin,
            reason = com.terraworld.api.wallet.WalletTransactionService.REASON_AD_REWARD,
            referenceId = adLog.id,
        )

        // P-WILT-001 + J-WILT-001 (UltraPlan v3, 2026-05-18):
        // 광고 시청 시 시들기 상태 reset. terrarium.wiltRecoveredAt 갱신 →
        // 다음 computeWiltingState 호출 시 days = 0 → stage 0 (FRESH).
        // 권장 default: 햇살 +1 만 (이슬 보너스 X, 이중 보상 회피).
        val wiltReset =
            terrariumRepository
                .findByUserId(userId)
                .map { terrarium ->
                    terrarium.wiltRecoveredAt = KstTime.now()
                    terrarium.updatedAt = KstTime.now()
                    terrariumRepository.save(terrarium)
                    true
                }.orElse(false)

        val newCount = (watchedToday + 1).toInt()
        auditService.publish(
            userId = userId,
            action = "REWARD_AD_CLAIMED",
            resourceType = "Reward",
            payload =
                mapOf(
                    "specialCoins" to REWARD_SPECIAL_COINS,
                    "dailyWatchCount" to newCount,
                    "wiltReset" to wiltReset,
                ),
        )
        return AdRewardResponse(
            reward = AdRewardResponseReward(specialCoins = REWARD_SPECIAL_COINS),
            dailyWatchCount = newCount,
            remainingToday = DAILY_AD_LIMIT - newCount,
            updatedCurrency = currencyBuilder.build(userId, user),
        )
    }
}
