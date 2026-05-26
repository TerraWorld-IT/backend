package com.terraworld.api.reward

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
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
    private val terrariumRepository: TerrariumRepository,
    private val redisTemplate: StringRedisTemplate,
    private val currencyBuilder: CurrencyBuilder,
    private val auditService: AuditService,
    // N2 (구현 계획서 v4): 광고 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    // N9 (구현 계획서 v4, 2026-05-26): nonce 중복 사용 dedup (replay 차단)
    private val adRewardNonceInboxRepository: AdRewardNonceInboxRepository,
    // N9 (Codex MEDIUM 4): nonce 강제 여부 flag — Phase 4 비공개 테스트 종료 후 true 전환.
    // false (default) 시 legacy path 허용, true 시 nonce 미제공 시 BAD_REQUEST.
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

    /**
     * ARCH-008-phase-5: 반환 타입을 generated [AdRewardResponse] 로 직접. local
     * `AdRewardResponsePayload` / `AdRewardPayload` 삭제. controller mapper 제거.
     *
     * N9 (구현 계획서 v4, 2026-05-26): nonce param 추가 — client 발급 UUID v4 또는 AdMob
     * SSV callback 의 transaction_id. `ad_reward_nonce_inbox` 에 INSERT 하여 중복 사용 차단.
     * nonce=null 은 backward-compat 경로 (warn log + audit legacy=true + 즉시 지급) —
     * Phase 4 비공개 테스트 종료 후 config flag `reward.ad.nonce.required=true` 전환 예정.
     *
     * Codex audit (Q4/Q5) 반영: SSV signature 검증 (ECDSA P-256 SHA-256) 은 별 endpoint
     * (`/rewards/ad/ssv-callback`) 의 raw query string layer. 본 service 는 nonce dedup
     * 만 — Google AdMob 공식 spec 의 signature 위치와 일치.
     */
    @Transactional
    fun claimAdReward(
        userId: String,
        nonce: String? = null,
        nonceSource: String = AdRewardNonceInbox.SOURCE_CLIENT,
    ): AdRewardResponse {
        // N9 (Codex MEDIUM 4): flag 활성 시 nonce 강제 — Phase 4 비공개 테스트 종료 후 전환.
        if (nonceRequired && nonce.isNullOrBlank()) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }

        // N9 (Codex MEDIUM 2): nonce 형식 검증 — blank / length 범위 외 차단.
        // openapi schema 가 minLength 16 / maxLength 64 선언하나 service boundary 에서도 강제.
        // (DB VARCHAR(64) persistence error 회피, 잘못된 빠른 호출 정책 차단).
        if (nonce != null && (nonce.isBlank() || nonce.length !in 16..64)) {
            throw BusinessException(ErrorCode.INVALID_INPUT)
        }

        // N9: nonce dedup 우선 처리 — 위조 호출 또는 retry 시 즉시 차단.
        // nonce 미제공 시 backward-compat 경로 (Codex Q3: warn log + audit 'legacy=true').
        val legacyPath = nonce == null
        if (nonce != null) {
            val inserted = adRewardNonceInboxRepository.insertIfAbsent(nonce, userId, nonceSource)
            if (inserted == 0) {
                throw BusinessException(ErrorCode.NONCE_ALREADY_CONSUMED)
            }
        } else {
            // Phase 4 비공개 테스트 종료 후 본 분기는 reward.ad.nonce.required=true 로 차단 예정.
            // 운영 모니터링 hook — Micrometer counter 추가 시 본 로그를 metric 으로 격상.
            log.warn(
                "claimAdReward legacy path — nonce 미제공. userId={} (Phase 4 종료 후 deprecate)",
                userId,
            )
        }

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
                    // N9 (Codex Q5): nonce 발급 경로 audit — 운영 후 nonce required 전환 시
                    // legacy 비율 측정에 사용. nonce 본체는 PII/replay 우려로 로그 X.
                    "nonceProvided" to !legacyPath,
                    "nonceSource" to if (legacyPath) "NONE" else nonceSource,
                    "legacy" to legacyPath,
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
