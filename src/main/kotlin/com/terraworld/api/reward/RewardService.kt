package com.terraworld.api.reward

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalTime

@Service
class RewardService(
    private val adWatchLogRepository: AdWatchLogRepository,
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
) {

    companion object {
        const val DAILY_AD_LIMIT = 5
        const val REWARD_SPECIAL_COINS = 1
        // 카테고리 id → UserService 와 동일 규약 (walk=1, read=2, run=3, draw=4)
        private const val CATEGORY_ID_WALK = 1L
        private const val CATEGORY_ID_READ = 2L
        private const val CATEGORY_ID_RUN = 3L
        private const val CATEGORY_ID_DRAW = 4L
    }

    @Transactional
    fun claimAdReward(userId: String): AdRewardResponsePayload {
        val user = userRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val today = LocalDate.now()
        val dayStart = today.atStartOfDay()
        val dayEnd = today.atTime(LocalTime.MAX)
        val watchedToday = adWatchLogRepository.countByUserIdAndWatchedAtBetween(userId, dayStart, dayEnd)

        if (watchedToday >= DAILY_AD_LIMIT) {
            throw BusinessException(ErrorCode.AD_DAILY_LIMIT_EXCEEDED)
        }

        user.specialCoin += REWARD_SPECIAL_COINS
        userRepository.save(user)
        adWatchLogRepository.save(
            AdWatchLog(userId = userId, rewardSpecialCoins = REWARD_SPECIAL_COINS)
        )

        val newCount = (watchedToday + 1).toInt()
        val tokenMap = userTokenRepository.findAllByUserId(userId)
            .associate { it.category.id to it.amount }

        return AdRewardResponsePayload(
            reward = AdRewardPayload(specialCoins = REWARD_SPECIAL_COINS),
            dailyWatchCount = newCount,
            remainingToday = DAILY_AD_LIMIT - newCount,
            updatedCurrency = CurrencySnapshot(
                basicCoins = user.basicCoin,
                specialCoins = user.specialCoin,
                walkTokens = tokenMap[CATEGORY_ID_WALK] ?: 0L,
                readTokens = tokenMap[CATEGORY_ID_READ] ?: 0L,
                runTokens = tokenMap[CATEGORY_ID_RUN] ?: 0L,
                drawTokens = tokenMap[CATEGORY_ID_DRAW] ?: 0L,
            ),
        )
    }
}
