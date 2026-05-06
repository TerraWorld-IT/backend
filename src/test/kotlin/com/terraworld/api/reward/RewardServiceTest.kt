package com.terraworld.api.reward

import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdWatchLog
import com.terraworld.domain.reward.AdWatchLogRepository
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
 * RewardService 의 분기 커버. 일일 한도 / 해피 패스 / 사용자 미존재 / 날짜 경계.
 * Spring context 없이 in-memory fake + StringRedisTemplate Mockito 모킹으로 빠르게 돈다.
 *
 * Redis 는 graceful degradation 경로 (DB 폴백) 를 그대로 통과시키도록
 * `increment` 가 1 부터 시퀀셜 카운트를 반환하도록 stub. DB 검증이 source of truth.
 */
class RewardServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var logRepo: FakeAdWatchLogRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var valueOps: ValueOperations<String, String>
    private lateinit var currencyBuilder: CurrencyBuilder
    private lateinit var service: RewardService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        logRepo = FakeAdWatchLogRepository()
        tokenRepo = FakeUserTokenRepository()
        redisTemplate = mock(StringRedisTemplate::class.java)

        @Suppress("UNCHECKED_CAST")
        valueOps = mock(ValueOperations::class.java) as ValueOperations<String, String>
        `when`(redisTemplate.opsForValue()).thenReturn(valueOps)

        // Redis 카운터: 호출마다 1, 2, 3, ... 반환 (DB 검증과 정합)
        var redisCount = 0L
        `when`(valueOps.increment(anyString())).thenAnswer { ++redisCount }
        // expire 는 호출만 받고 무시
        lenient().`when`(redisTemplate.expire(anyString(), any())).thenReturn(true)

        currencyBuilder = CurrencyBuilder(tokenRepo)
        service = RewardService(logRepo, userRepo, redisTemplate, currencyBuilder)

        userRepo.save(User(id = "user-1", nickname = "테스터"))
    }

    @Test
    fun `claimAdReward 해피 패스 — 스페셜 코인 +1 과 오늘 카운트 +1 반환`() {
        val beforeSpecial = userRepo.findById("user-1").get().specialCoin

        val response = service.claimAdReward("user-1")

        assertEquals(RewardService.REWARD_SPECIAL_COINS, response.reward.specialCoins)
        assertEquals(1, response.dailyWatchCount)
        assertEquals(RewardService.DAILY_AD_LIMIT - 1, response.remainingToday)
        assertEquals(
            beforeSpecial + RewardService.REWARD_SPECIAL_COINS,
            userRepo.findById("user-1").get().specialCoin,
        )
        assertEquals(1, logRepo.all().size)
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
