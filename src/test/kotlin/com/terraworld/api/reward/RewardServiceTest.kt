package com.terraworld.api.reward

import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDateTime
import kotlin.test.assertEquals

/**
 * RewardService 의 분기 커버. 일일 한도 / 해피 패스 / 사용자 미존재 / 날짜 경계.
 * Spring context 없이 in-memory fake + StringRedisTemplate Mockito 모킹으로 빠르게 돈다.
 *
 * Redis 는 graceful degradation 경로 (DB 폴백) 를 그대로 통과시키도록
 * `increment` 가 1 부터 시퀀셜 카운트를 반환하도록 stub. DB 검증이 source of truth.
 */
class RewardServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var logRepo: FakeAdWatchLogRepository
    private lateinit var nonceInboxRepository: AdRewardNonceInboxRepository
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var walletBuilder: WalletBuilder
    private lateinit var service: RewardService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        logRepo = FakeAdWatchLogRepository()
        redisTemplate = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)

        // Redis 카운터: 호출마다 1, 2, 3, ... 반환 (DB 검증과 정합)
        var redisCount = 0L
        `when`(valueOps.increment(anyString())).thenAnswer { ++redisCount }
        // expire 는 호출만 받고 무시
        lenient().`when`(redisTemplate.expire(anyString(), any())).thenReturn(true)

        walletBuilder =
            WalletBuilder(
                org.mockito.Mockito.mock(com.terraworld.api.currency.CurrencyService::class.java).also {
                    org.mockito.kotlin
                        .whenever(it.currencyResponse(org.mockito.kotlin.any()))
                        .thenReturn(
                            io.terraworld.api.model
                                .CurrencyResponse(balances = emptyList()),
                        )
                },
            )
        val auditService = mock(AuditService::class.java)
        // N1 fix: RewardService 생성자에 terrariumRepository 추가됨 (광고 시청 시 시들기 reset).
        // 본 test 는 wilt reset 경로를 검증하지 않으므로 mock — findByUserId 는 Optional.empty() 반환 →
        // claimAdReward 의 wiltReset = false 로 happy path 통과.
        val terrariumRepository = mock(TerrariumRepository::class.java)
        // N2 fix: RewardService 생성자에 walletTransactionService 추가됨 (광고 보상 ledger).
        // 본 test 는 ledger 경로를 검증하지 않으므로 mock — append() 반환값은 미사용.
        val walletTransactionService = mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        // N9 (구현 계획서 v4, 2026-05-26): nonce inbox mock — 본 test 는 nonce 미전달
        // (backward-compat 경로) 만 검증하므로 insertIfAbsent 는 호출되지 않음. nonce 검증 분기
        // 자체의 test 는 별 cycle (RewardServiceNonceTest).
        nonceInboxRepository = mock(AdRewardNonceInboxRepository::class.java)
        service =
            RewardService(
                logRepo,
                userRepo,
                mock(com.terraworld.api.currency.CurrencyService::class.java),
                terrariumRepository,
                redisTemplate,
                walletBuilder,
                auditService,
                walletTransactionService,
                nonceInboxRepository,
                nonceRequired = false,
            )

        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    @Test
    fun `claimAdReward 해피 패스 — 스페셜 코인 +1 과 오늘 카운트 +1 반환`() {
        val response = service.claimAdReward("user-1")

        assertEquals(RewardService.REWARD_SPECIAL_COINS, response.reward.specialCoins)
        assertEquals(1, response.dailyWatchCount)
        assertEquals(RewardService.DAILY_AD_LIMIT - 1, response.remainingToday)
        assertEquals(1, logRepo.all().size)

        // Codex LOW 5: legacy path 는 inbox 에 절대 INSERT 하지 않음을 verify 로 잠금.
        verify(nonceInboxRepository, never()).insertIfAbsent(anyString(), anyString(), anyString())
    }

    @Test
    fun `claimAdReward — 연속 5회 호출 후 6회째는 AD_DAILY_LIMIT_EXCEEDED`() {
        repeat(RewardService.DAILY_AD_LIMIT) {
            service.claimAdReward("user-1")
        }

        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1")
            }
        assertEquals(ErrorCode.AD_DAILY_LIMIT_EXCEEDED, ex.errorCode)
        assertEquals(RewardService.DAILY_AD_LIMIT, logRepo.all().size)
    }

    @Test
    fun `claimAdReward — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("ghost")
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
        assertEquals(0, logRepo.all().size)
    }

    @Test
    fun `claimAdReward — 마지막 호출에서 remainingToday 는 0`() {
        repeat(RewardService.DAILY_AD_LIMIT - 1) {
            service.claimAdReward("user-1")
        }

        val last = service.claimAdReward("user-1")

        assertEquals(RewardService.DAILY_AD_LIMIT, last.dailyWatchCount)
        assertEquals(0, last.remainingToday)
    }

    @Test
    fun `claimAdReward — 어제 광고 로그는 오늘 카운트에 포함 안 됨`() {
        // 어제 4회 로그 미리 삽입
        repeat(4) {
            logRepo.save(
                AdWatchLog(
                    userId = "user-1",
                    watchedAt = LocalDateTime.now().minusDays(1),
                ),
            )
        }

        val response = service.claimAdReward("user-1")

        assertEquals(1, response.dailyWatchCount)
        assertEquals(RewardService.DAILY_AD_LIMIT - 1, response.remainingToday)
    }

    // ─── Fakes ──────────────────────────────────────────────
    private class FakeAdWatchLogRepository :
        FakeJpaRepository<AdWatchLog, Long>(),
        AdWatchLogRepository {
        private var nextId = 1L

        override fun extractId(entity: AdWatchLog): Long = entity.id

        override fun assignId(entity: AdWatchLog): AdWatchLog {
            if (entity.id != 0L) return entity
            val idField = AdWatchLog::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
        }

        override fun acquireAdRewardDailyLock(key: String): Int = 1

        override fun countByUserIdAndWatchedAtBetween(
            userId: String,
            from: LocalDateTime,
            to: LocalDateTime,
        ): Long =
            store.values
                .count {
                    it.userId == userId &&
                        !it.watchedAt.isBefore(from) &&
                        !it.watchedAt.isAfter(to)
                }.toLong()
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }
}
