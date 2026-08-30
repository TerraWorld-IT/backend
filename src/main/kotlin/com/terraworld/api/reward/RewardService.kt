package com.terraworld.api.reward

import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.AdRewardResponse
import io.terraworld.api.model.AdRewardResponseReward
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
    // 낙서장 P1 cutover(dual-write): 광고 보상 = 루비(RUBY) 정규화 잔액에도 반영
    private val currencyService: com.terraworld.api.currency.CurrencyService,
    private val terrariumRepository: TerrariumRepository,
    private val redisTemplate: StringRedisTemplate,
    private val walletBuilder: WalletBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 광고 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    private val adRewardNonceService: AdRewardNonceService,
    // legacy/shadow 의 nonce 누락 호환 정책. authoritative 는 이 값과 무관하게 서버 nonce 를 강제한다.
    @Value("\${reward.ad.nonce.required:false}")
    private val nonceRequired: Boolean = false,
) {
    companion object {
        const val DAILY_AD_LIMIT = 5
        const val REWARD_SPECIAL_COINS = 1

        // 앱 namespace prefix — 같은 Redis 인스턴스를 다른 시스템과 공유 시 키 충돌 방지.
        private const val REDIS_DAILY_KEY_PREFIX = "terraworld:reward:ad:daily:"

        private val log = LoggerFactory.getLogger(RewardService::class.java)
    }

    /** 광고 보상과 키우기 AD revive 가 공유하는 단계별 nonce 소비 진입점. */
    @Transactional
    fun consumeAdNonce(
        userId: String,
        nonce: String?,
        purpose: String,
    ) {
        adRewardNonceService.consume(userId, nonce, purpose)
    }

    /**
     * ARCH-008-phase-5: 반환 타입을 generated [AdRewardResponse] 로 직접. local
     * `AdRewardResponsePayload` / `AdRewardPayload` 삭제. controller mapper 제거.
     *
     * legacy/shadow 는 기존 client nonce 지급을 유지한다. authoritative 는 서버 발급 nonce 가
     * SSV callback 의 서명 검증과 custom_data 결합을 마친 경우에만 같은 지급 로직으로 진입한다.
     */

    @Transactional
    fun claimAdReward(
        userId: String,
        nonce: String? = null,
        nonceSource: String = AdRewardNonceInbox.SOURCE_CLIENT,
    ): AdRewardResponse {
        if ((nonceRequired || adRewardNonceService.isAuthoritative()) && nonce.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }

        // nonce 소비와 실제 지급은 같은 DB transaction 이므로 이후 한도/지급 실패 시 소비도 rollback 된다.
        val consumedNonceSource =
            if (nonce != null) {
                adRewardNonceService.consume(
                    userId = userId,
                    nonce = nonce,
                    purpose = AdRewardNonceInbox.PURPOSE_AD_REWARD,
                    legacySource = nonceSource,
                )
            } else {
                log.warn(
                    "event=reward.ad.legacy-no-nonce userId={} mode={}",
                    userId,
                    adRewardNonceService.modeValue(),
                )
                "NONE"
            }
        val legacyPath = nonce == null

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
        // R10 AD-REWARD-REDIS-FALLBACK-RACE: Redis 장애 시 이 DB 경로가 유일 cap 가드가 되는데 count-then-insert
        //   가 무락이면 동시 요청(서로 다른 nonce)이 같은 count 를 보고 일일 cap(5)을 초과 mint 한다. per-(userId|date)
        //   advisory lock 으로 count+insert 를 직렬화(Redis 정상 시엔 저빈도라 사실상 무비용, tx 종료 시 자동 해제).
        adWatchLogRepository.acquireAdRewardDailyLock("ad-reward|$userId|$today")
        val dayStart = today.atStartOfDay()
        val dayEnd = today.atTime(LocalTime.MAX)
        val watchedToday = adWatchLogRepository.countByUserIdAndWatchedAtBetween(userId, dayStart, dayEnd)

        if (watchedToday >= DAILY_AD_LIMIT) {
            throw BusinessException(ErrorCode.AD_DAILY_LIMIT_EXCEEDED)
        }

        val adLog =
            adWatchLogRepository.save(
                AdWatchLog(userId = userId, rewardSpecialCoins = REWARD_SPECIAL_COINS),
            )
        // 낙서장 P1: 광고 보상 = 신 substrate RUBY credit 단일 SoT (credit 이 잔액+원장 원자 처리)
        currencyService.credit(
            userId,
            com.terraworld.domain.currency.CurrencyCode.RUBY,
            REWARD_SPECIAL_COINS.toLong(),
            com.terraworld.api.wallet.WalletTransactionService.REASON_AD_REWARD,
            "AD_REWARD",
            adLog.id.toString(),
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
                    // N9 (Codex Q5): nonce 발급 경로 audit — 운영 후 nonce required 전환 시
                    // legacy 비율 측정에 사용. nonce 본체는 PII/replay 우려로 로그 X.
                    "nonceProvided" to !legacyPath,
                    "nonceSource" to consumedNonceSource,
                    "legacy" to legacyPath,
                    "adMode" to adRewardNonceService.modeValue(),
                ),
        )
        return AdRewardResponse(
            reward = AdRewardResponseReward(specialCoins = REWARD_SPECIAL_COINS),
            dailyWatchCount = newCount,
            remainingToday = DAILY_AD_LIMIT - newCount,
            updatedCurrency = walletBuilder.build(userId, user),
        )
    }
}
