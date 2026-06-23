package com.terraworld.api.exchange

import com.terraworld.api.exchange.dto.SpecialToBasicRequest
import com.terraworld.api.exchange.dto.TokenExchangeRequest
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.exchange.TokenExchangeRate
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import kotlin.test.assertEquals

/**
 * ExchangeService 의 분기 커버.
 * - specialToBasic: 해피 / 잔고 부족 / 사용자 미존재
 * - exchangeTokens: 해피 (rate=2.0 → 10 보내고 5 받음) / 같은 카테고리 / 환율 미존재 /
 *                  보낼 토큰 잔고 부족 / 보낼 토큰 row 부재 (categoryId 미보유)
 */
class ExchangeServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var rateRepo: FakeRateRepository
    private lateinit var service: ExchangeService

    private lateinit var walkCategory: Category
    private lateinit var readCategory: Category
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        tokenRepo = FakeUserTokenRepository()
        rateRepo = FakeRateRepository()
        val walletBuilder = WalletBuilder(tokenRepo)
        val auditService = org.mockito.Mockito.mock(AuditService::class.java)
        // N2/N15 (구현 계획서 v4): ExchangeService 생성자에 walletTransactionService +
        // categoryRepository 추가. 본 test 는 ledger / token→basic 경로 미검증 — mock.
        val walletTransactionService =
            org.mockito.Mockito.mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        val categoryRepository =
            org.mockito.Mockito.mock(com.terraworld.domain.category.CategoryRepository::class.java)
        service =
            ExchangeService(
                userRepo,
                tokenRepo,
                rateRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
                categoryRepository,
            )

        walkCategory = Category(id = 1L, name = "산책", tokenName = "산책토큰")
        readCategory = Category(id = 2L, name = "독서", tokenName = "독서토큰")

        user = User(id = "user-1", nickname = "테스터", basicCoin = 0, specialCoin = 10)
        userRepo.save(user)
        tokenRepo.save(UserToken(id = 1L, user = user, category = walkCategory, amount = 10))
        tokenRepo.save(UserToken(id = 2L, user = user, category = readCategory, amount = 0))

        rateRepo.save(
            TokenExchangeRate(
                id = 1L,
                fromCategory = walkCategory,
                toCategory = readCategory,
                rate = 2.0f,
                isActive = true,
            ),
        )
    }

    // ─── specialToBasic ────────────────────────────────────────

    @Test
    fun `specialToBasic 해피 패스 — 5 스페셜 = 10 기본`() {
        val response = service.specialToBasic("user-1", SpecialToBasicRequest(amount = 5))

        assertEquals("SPECIAL_COIN", response.exchanged.fromType)
        assertEquals(5, response.exchanged.fromAmount)
        assertEquals("BASIC_COIN", response.exchanged.toType)
        assertEquals(10, response.exchanged.toAmount)
        assertEquals(2.0, response.exchanged.rate)

        val u = userRepo.findById("user-1").get()
        assertEquals(5, u.specialCoin)
        assertEquals(10, u.basicCoin)
    }

    @Test
    fun `specialToBasic — 보유한 스페셜보다 큰 amount 는 INSUFFICIENT_FUNDS`() {
        val ex =
            assertThrows<BusinessException> {
                service.specialToBasic("user-1", SpecialToBasicRequest(amount = 11))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)

        // 잔고는 변경되면 안 됨
        val u = userRepo.findById("user-1").get()
        assertEquals(10, u.specialCoin)
        assertEquals(0, u.basicCoin)
    }

    @Test
    fun `specialToBasic — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.specialToBasic("ghost", SpecialToBasicRequest(amount = 1))
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    // ─── exchangeTokens ────────────────────────────────────────

    @Test
    fun `exchangeTokens 해피 패스 — rate=2 일 때 10 보내고 5 받음`() {
        val response = service.exchangeTokens("user-1", TokenExchangeRequest(1L, 2L, 10))

        assertEquals(10, response.exchanged.fromAmount)
        assertEquals(5, response.exchanged.toAmount)
        // 응답의 rate 는 received/sent — 0.5
        assertEquals(0.5, response.exchanged.rate)

        val walk = tokenRepo.findByUserIdAndCategoryId("user-1", 1L).get()
        val read = tokenRepo.findByUserIdAndCategoryId("user-1", 2L).get()
        assertEquals(0, walk.amount)
        assertEquals(5, read.amount)
    }

    @Test
    fun `exchangeTokens — 같은 카테고리 교환은 SAME_CATEGORY_EXCHANGE`() {
        val ex =
            assertThrows<BusinessException> {
                service.exchangeTokens("user-1", TokenExchangeRequest(1L, 1L, 5))
            }
        assertEquals(ErrorCode.SAME_CATEGORY_EXCHANGE, ex.errorCode)
    }

    @Test
    fun `exchangeTokens — 등록된 환율이 없으면 EXCHANGE_RATE_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                // (2 → 1) 방향은 등록 안 함
                service.exchangeTokens("user-1", TokenExchangeRequest(2L, 1L, 5))
            }
        assertEquals(ErrorCode.EXCHANGE_RATE_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `exchangeTokens — 보낼 토큰 잔고 부족은 INSUFFICIENT_FUNDS`() {
        val ex =
            assertThrows<BusinessException> {
                // walk 토큰 보유 10 → 11 보내려 함
                service.exchangeTokens("user-1", TokenExchangeRequest(1L, 2L, 11))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `exchangeTokens — 보낼 카테고리의 token row 가 없으면 INSUFFICIENT_FUNDS`() {
        // 새 사용자 — UserToken row 자체가 없음
        val ghost = User(id = "user-2", nickname = "ghost")
        userRepo.save(ghost)

        // (walk→read) 환율은 존재하지만 user-2 의 walk token row 가 없음
        val ex =
            assertThrows<BusinessException> {
                service.exchangeTokens("user-2", TokenExchangeRequest(1L, 2L, 1))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    // ─── Fakes ─────────────────────────────────────────────────

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

    private class FakeRateRepository :
        FakeJpaRepository<TokenExchangeRate, Long>(),
        TokenExchangeRateRepository {
        override fun extractId(entity: TokenExchangeRate): Long = entity.id

        override fun findByFromCategoryIdAndToCategoryIdAndIsActiveTrue(
            fromId: Long,
            toId: Long,
        ): Optional<TokenExchangeRate> =
            Optional.ofNullable(
                store.values.firstOrNull {
                    it.fromCategory.id == fromId && it.toCategory.id == toId && it.isActive
                },
            )
    }
}
