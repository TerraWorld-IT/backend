package com.terraworld.api.reward

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals

/**
 * N9 (구현 계획서 v4, 2026-05-26): nonce dedup 분기 전용 test.
 *
 * RewardServiceTest 가 backward-compat 경로 (nonce=null) 만 검증하므로,
 * 본 class 는 nonce 가 있을 때의 4 시나리오를 cover:
 *   1. 첫 nonce 사용 → 정상 지급 + inbox INSERT
 *   2. 같은 nonce 두 번째 → NONCE_ALREADY_CONSUMED (replay 차단)
 *   3. 다른 nonce 시퀀셜 → 정상 (daily limit 내)
 *   4. CLIENT vs SSV_CALLBACK source 둘 다 같은 inbox 사용
 */
class RewardServiceNonceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var logRepo: FakeAdWatchLogRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var nonceInbox: FakeAdRewardNonceInboxRepository
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var currencyBuilder: CurrencyBuilder
    private lateinit var service: RewardService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        logRepo = FakeAdWatchLogRepository()
        tokenRepo = FakeUserTokenRepository()
        nonceInbox = FakeAdRewardNonceInboxRepository()
        redisTemplate = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)

        var redisCount = 0L
        `when`(valueOps.increment(anyString())).thenAnswer { ++redisCount }
        lenient().`when`(redisTemplate.expire(anyString(), any())).thenReturn(true)

        currencyBuilder = CurrencyBuilder(tokenRepo)
        val auditService = mock(AuditService::class.java)
        val terrariumRepository = mock(TerrariumRepository::class.java)
        val walletTransactionService = mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        service =
            RewardService(
                logRepo,
                userRepo,
                terrariumRepository,
                redisTemplate,
                currencyBuilder,
                auditService,
                walletTransactionService,
                nonceInbox,
                nonceRequired = false,
            )

        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    @Test
    fun `nonce 첫 사용 — 정상 지급 + inbox INSERT`() {
        val nonce = "550e8400-e29b-41d4-a716-446655440000"
        val response = service.claimAdReward("user-1", nonce)

        assertEquals(RewardService.REWARD_SPECIAL_COINS, response.reward.specialCoins)
        assertEquals(1, nonceInbox.all().size)
        assertEquals(nonce, nonceInbox.all().first().nonce)
    }

    @Test
    fun `같은 nonce 두 번째 — NONCE_ALREADY_CONSUMED throw + 두 번째 지급 안 됨`() {
        val nonce = "550e8400-e29b-41d4-a716-446655440000"
        service.claimAdReward("user-1", nonce)
        val firstSpecial = userRepo.findById("user-1").get().specialCoin

        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1", nonce)
            }

        assertEquals(ErrorCode.NONCE_ALREADY_CONSUMED, ex.errorCode)
        assertEquals(firstSpecial, userRepo.findById("user-1").get().specialCoin)
        assertEquals(1, logRepo.all().size)
        assertEquals(1, nonceInbox.all().size)
    }

    @Test
    fun `다른 nonce 시퀀셜 — 정상 (daily limit 내)`() {
        // nonce length >= 16 충족 (Codex MEDIUM 2 형식 검증).
        service.claimAdReward("user-1", "nonce-aaaaaaaaaaaaaaaa")
        service.claimAdReward("user-1", "nonce-bbbbbbbbbbbbbbbb")
        service.claimAdReward("user-1", "nonce-cccccccccccccccc")

        assertEquals(3, logRepo.all().size)
        assertEquals(3, nonceInbox.all().size)
    }

    @Test
    fun `SSV_CALLBACK source 도 같은 inbox 공유 — CLIENT 와 같은 nonce 충돌`() {
        val nonce = "shared-nonce-1234567890abcdef"
        service.claimAdReward("user-1", nonce, AdRewardNonceInbox.SOURCE_CLIENT)

        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1", nonce, AdRewardNonceInbox.SOURCE_SSV_CALLBACK)
            }
        assertEquals(ErrorCode.NONCE_ALREADY_CONSUMED, ex.errorCode)
    }

    // Codex MEDIUM 2 — nonce 형식 검증 case (blank / too short / too long).
    @Test
    fun `nonce blank 은 INVALID_INPUT throw + inbox INSERT 안 됨`() {
        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1", "   ")
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        assertEquals(0, nonceInbox.all().size)
    }

    @Test
    fun `nonce length 16 미만은 INVALID_INPUT throw`() {
        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1", "short-nonce")
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        assertEquals(0, nonceInbox.all().size)
    }

    @Test
    fun `nonce length 64 초과는 INVALID_INPUT throw`() {
        val tooLong = "x".repeat(65)
        val ex =
            assertThrows<BusinessException> {
                service.claimAdReward("user-1", tooLong)
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        assertEquals(0, nonceInbox.all().size)
    }

    // Codex MEDIUM 4 — nonceRequired=true flag test (별 setup).
    @Test
    fun `nonceRequired flag 활성 + nonce null 시 INVALID_INPUT throw`() {
        val strictService =
            RewardService(
                logRepo,
                userRepo,
                mock(TerrariumRepository::class.java),
                redisTemplate,
                currencyBuilder,
                mock(AuditService::class.java),
                mock(com.terraworld.api.wallet.WalletTransactionService::class.java),
                nonceInbox,
                nonceRequired = true,
            )

        val ex =
            assertThrows<BusinessException> {
                strictService.claimAdReward("user-1", null)
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        assertEquals(0, nonceInbox.all().size)
    }

    @Test
    fun `nonceRequired flag 활성 + valid nonce 정상 지급`() {
        val strictService =
            RewardService(
                logRepo,
                userRepo,
                mock(TerrariumRepository::class.java),
                redisTemplate,
                currencyBuilder,
                mock(AuditService::class.java),
                mock(com.terraworld.api.wallet.WalletTransactionService::class.java),
                nonceInbox,
                nonceRequired = true,
            )

        val response = strictService.claimAdReward("user-1", "550e8400-e29b-41d4-a716-446655440000")
        assertEquals(RewardService.REWARD_SPECIAL_COINS, response.reward.specialCoins)
    }

    // ─── Fakes ──────────────────────────────────────────────
    private class FakeAdRewardNonceInboxRepository :
        FakeJpaRepository<AdRewardNonceInbox, String>(),
        AdRewardNonceInboxRepository {
        override fun extractId(entity: AdRewardNonceInbox): String = entity.nonce

        override fun insertIfAbsent(
            nonce: String,
            userId: String,
            source: String,
        ): Int {
            if (store.containsKey(nonce)) return 0
            store[nonce] = AdRewardNonceInbox(nonce = nonce, userId = userId, source = source)
            return 1
        }
    }

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

    private class FakeUserTokenRepository :
        FakeJpaRepository<UserToken, Long>(),
        UserTokenRepository {
        override fun extractId(entity: UserToken): Long = entity.id

        override fun findByUserIdAndCategoryId(
            userId: String,
            categoryId: Long,
        ): Optional<UserToken> =
            Optional.ofNullable(
                store.values.firstOrNull { it.user.id == userId && it.category.id == categoryId },
            )

        override fun findAllByUserId(userId: String): List<UserToken> = store.values.filter { it.user.id == userId }
    }
}
