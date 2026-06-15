package com.terraworld.api.record

import com.terraworld.api.record.dto.CreateRecordRequest
import com.terraworld.api.user.CurrencyBuilder
import com.terraworld.api.user.UserLevelService
import com.terraworld.api.wallet.WalletTransactionService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RecordService.createRecord 의 핵심 분기 커버.
 *
 * - 통제가 필요한 repo (record / user / userToken / category) 는 FakeXxxRepository in-memory 구현.
 * - InviteRepository 는 비-partner 경로에서 미사용 → Mockito mock.
 * - UserLevelService / WalletTransactionService 는 concrete @Service — side-effect 협력자라 Mockito mock
 *   후 호출 여부만 verify (level_config seed / ledger DB 의존성 회피).
 * - CurrencyBuilder 는 ExchangeServiceTest 와 동일하게 실제 객체 (tokenRepo 주입) 사용.
 *
 * 커버:
 *  - 해피 패스: EXP +10 누적 + basicCoin/token 보상 가산 + record 저장 + level-up hook 호출 + ledger append x2
 *  - dailyLimit 도달 시 DAILY_LIMIT_EXCEEDED (todayCount >= dailyLimit)
 *  - dailyLimit 직전 (todayCount = limit-1) 은 정상 통과
 *  - 사용자 미존재 USER_NOT_FOUND / 카테고리 미존재 CATEGORY_NOT_FOUND
 *
 * partner/joint-record 분기 (RecordService.kt:87-142):
 *  - 본인을 partner 로 지정 시 INVALID_PARTNER
 *  - 존재하지 않는 partner 지정 시 INVALID_PARTNER
 *  - 친구 아님(existsAcceptedBetween=false) 시 INVALID_PARTNER
 *  - 해피 co-record: 양쪽 record 저장 (동일 jointSessionId, 상호 partnerUserId) + partner 도 보상 가산
 */
class RecordServiceTest {
    private lateinit var recordRepo: FakeRecordRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var categoryRepo: FakeCategoryRepository
    private lateinit var inviteRepo: InviteRepository
    private lateinit var userLevelService: UserLevelService
    private lateinit var walletTransactionService: WalletTransactionService
    private lateinit var service: RecordService

    private lateinit var user: User
    private lateinit var walkCategory: Category

    @BeforeEach
    fun setup() {
        recordRepo = FakeRecordRepository()
        userRepo = FakeUserRepository()
        tokenRepo = FakeUserTokenRepository()
        categoryRepo = FakeCategoryRepository()
        inviteRepo = mock(InviteRepository::class.java)
        userLevelService = mock(UserLevelService::class.java)
        walletTransactionService = mock(WalletTransactionService::class.java)
        val currencyBuilder = CurrencyBuilder(tokenRepo)

        service =
            RecordService(
                recordRepo,
                userRepo,
                tokenRepo,
                categoryRepo,
                inviteRepo,
                currencyBuilder,
                userLevelService,
                walletTransactionService,
            )

        user = User(id = "user-1", nickname = "테스터", basicCoin = 0, totalExp = 0, level = 1)
        userRepo.save(user)

        // baseCoinReward=20, baseTokenReward=10, dailyLimit=5 (default)
        walkCategory =
            Category(
                id = 1L,
                name = "산책",
                tokenName = "산책토큰",
                baseCoinReward = 20,
                baseTokenReward = 10,
                dailyLimit = 5,
            )
        categoryRepo.save(walkCategory)
    }

    @Test
    fun `createRecord 해피 패스 — EXP 10 누적 + coin 20 + token 10 + record 저장 + ledger 기록 + level-up hook 호출`() {
        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))

        // 응답 reward 필드
        assertEquals(20, response.reward.basicCoins)
        assertEquals(10, response.reward.categoryTokens)
        assertEquals(10, response.reward.experienceGained)

        // 응답 record 필드
        assertEquals(1L, response.record.categoryId)
        assertEquals("산책", response.record.categoryName)

        // user 잔액/EXP 가산
        val u = userRepo.findById("user-1").get()
        assertEquals(20, u.basicCoin)
        assertEquals(10, u.totalExp)

        // record 1건 저장 (partner 없음)
        assertEquals(1, recordRepo.all().size)

        // token row 생성 + 가산
        val token = tokenRepo.findByUserIdAndCategoryId("user-1", 1L).get()
        assertEquals(10, token.amount)

        // updatedCurrency 가 token 반영
        assertEquals(20.0, response.updatedCurrency.basicCoins)
        assertEquals(10.0, response.updatedCurrency.walkTokens)

        // level-up hook 호출 검증
        verify(userLevelService).checkAndApplyLevelUp(eq(u))

        // wallet ledger append x2 (BASIC_COIN + CATEGORY_TOKEN)
        verify(walletTransactionService).append(
            user = eq(u),
            currencyType = eq(WalletTransactionService.CURRENCY_BASIC_COIN),
            amount = eq(20L),
            balanceAfter = eq(20L),
            reason = eq(WalletTransactionService.REASON_RECORD_REWARD),
            category = anyOrNull(),
            referenceId = anyOrNull(),
        )
        verify(walletTransactionService).append(
            user = eq(u),
            currencyType = eq(WalletTransactionService.CURRENCY_CATEGORY_TOKEN),
            amount = eq(10L),
            balanceAfter = eq(10L),
            reason = eq(WalletTransactionService.REASON_RECORD_REWARD),
            category = anyOrNull(),
            referenceId = anyOrNull(),
        )
    }

    @Test
    fun `createRecord — 두 번째 기록 시 EXP와 coin이 누적된다`() {
        service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))
        service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))

        val u = userRepo.findById("user-1").get()
        assertEquals(40, u.basicCoin)
        assertEquals(20, u.totalExp)

        val token = tokenRepo.findByUserIdAndCategoryId("user-1", 1L).get()
        assertEquals(20, token.amount)
        assertEquals(2, recordRepo.all().size)
    }

    @Test
    fun `createRecord — dailyLimit 도달 시 DAILY_LIMIT_EXCEEDED`() {
        // 이미 오늘 dailyLimit(5) 만큼 기록됨 → todayCount(5) >= dailyLimit(5)
        recordRepo.todayCountOverride = 5

        val ex =
            assertThrows<BusinessException> {
                service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))
            }
        assertEquals(ErrorCode.DAILY_LIMIT_EXCEEDED, ex.errorCode)

        // 보상/기록은 발생하면 안 됨
        val u = userRepo.findById("user-1").get()
        assertEquals(0, u.basicCoin)
        assertEquals(0, u.totalExp)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — dailyLimit 직전(limit-1)은 정상 통과`() {
        // todayCount=4 < dailyLimit=5 → 통과
        recordRepo.todayCountOverride = 4

        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))

        assertEquals(20, response.reward.basicCoins)
        assertEquals(1, recordRepo.all().size)
    }

    @Test
    fun `createRecord — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord("ghost", CreateRecordRequest(categoryId = 1L))
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `createRecord — 존재하지 않는 카테고리는 CATEGORY_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord("user-1", CreateRecordRequest(categoryId = 999L))
            }
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, ex.errorCode)
    }

    // ─── partner / joint-record 분기 (RecordService.kt:87-142) ──

    @Test
    fun `createRecord — 본인을 partner 로 지정하면 INVALID_PARTNER`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord(
                    "user-1",
                    CreateRecordRequest(categoryId = 1L, partnerUserId = "user-1"),
                )
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)

        // 검증 실패 시 본인 record/보상도 발생하지 않는다
        val u = userRepo.findById("user-1").get()
        assertEquals(0, u.basicCoin)
        assertEquals(0, u.totalExp)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 존재하지 않는 partner 는 INVALID_PARTNER`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord(
                    "user-1",
                    CreateRecordRequest(categoryId = 1L, partnerUserId = "ghost"),
                )
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)

        val u = userRepo.findById("user-1").get()
        assertEquals(0, u.basicCoin)
        assertEquals(0, u.totalExp)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 친구가 아닌 partner(existsAcceptedBetween=false)는 INVALID_PARTNER`() {
        userRepo.save(User(id = "partner-1", nickname = "짝꿍", basicCoin = 0, totalExp = 0, level = 1))
        // 친구 아님 — existsAcceptedBetween 가 false (mock 기본값이지만 명시 stub)
        whenever(inviteRepo.existsAcceptedBetween("user-1", "partner-1")).thenReturn(false)

        val ex =
            assertThrows<BusinessException> {
                service.createRecord(
                    "user-1",
                    CreateRecordRequest(categoryId = 1L, partnerUserId = "partner-1"),
                )
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)

        // partner 가 존재해도 친구 관계 아니면 본인 record/보상 미발생
        val u = userRepo.findById("user-1").get()
        assertEquals(0, u.basicCoin)
        assertEquals(0, u.totalExp)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 친구 partner 와의 co-record 는 양쪽 record 저장 + partner 도 보상 가산`() {
        userRepo.save(User(id = "partner-1", nickname = "짝꿍", basicCoin = 0, totalExp = 0, level = 1))
        // 친구 관계 성립 — joint record 통과
        whenever(inviteRepo.existsAcceptedBetween("user-1", "partner-1")).thenReturn(true)

        val response =
            service.createRecord(
                "user-1",
                CreateRecordRequest(categoryId = 1L, partnerUserId = "partner-1"),
            )

        // 양쪽 record 저장 (본인 + partner)
        val all = recordRepo.all()
        assertEquals(2, all.size)

        // 본인 record: partnerUserId = partner.id + jointSessionId 비-null
        val ownRecord = all.first { it.user.id == "user-1" }
        assertEquals("partner-1", ownRecord.partnerUserId)
        assertTrue(ownRecord.jointSessionId != null)

        // partner record: partnerUserId = 본인 id, 동일한 jointSessionId 공유
        val partnerRecord = all.first { it.user.id == "partner-1" }
        assertEquals("user-1", partnerRecord.partnerUserId)
        assertEquals(ownRecord.jointSessionId, partnerRecord.jointSessionId)

        // 본인 보상 가산
        val u = userRepo.findById("user-1").get()
        assertEquals(20, u.basicCoin)
        assertEquals(10, u.totalExp)

        // partner 도 보상 가산 (basicCoin/EXP + category token row 생성)
        val p = userRepo.findById("partner-1").get()
        assertEquals(20, p.basicCoin)
        assertEquals(10, p.totalExp)
        val partnerToken = tokenRepo.findByUserIdAndCategoryId("partner-1", 1L).get()
        assertEquals(10, partnerToken.amount)

        // 응답 reward 는 본인 기준
        assertEquals(20, response.reward.basicCoins)
        assertEquals(10, response.reward.categoryTokens)
        assertEquals(10, response.reward.experienceGained)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    /**
     * RecordRepository fake.
     *  - save 시 reflection 으로 auto-gen Long id 부여 (ActivityRecord.id 는 val 0).
     *  - countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse 는 dailyLimit 분기 통제용 —
     *    todayCountOverride 가 설정되면 그 값을, 아니면 store 기반 count 반환.
     */
    private class FakeRecordRepository :
        FakeJpaRepository<ActivityRecord, Long>(),
        RecordRepository {
        var todayCountOverride: Long? = null
        private var seq = 0L

        override fun extractId(entity: ActivityRecord): Long = entity.id

        override fun assignId(entity: ActivityRecord): ActivityRecord {
            if (entity.id == 0L) {
                val field = ActivityRecord::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.set(entity, ++seq)
            }
            return entity
        }

        override fun countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
            userId: String,
            recordedDate: LocalDate,
            categoryId: Long,
        ): Long =
            todayCountOverride
                ?: store.values
                    .count {
                        it.user.id == userId && it.category.id == categoryId && !it.isDeleted
                    }.toLong()

        override fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            pageable: Pageable,
        ): Page<ActivityRecord> = Page.empty()

        override fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
            userId: String,
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityRecord> = emptyList()

        override fun countByUserIdAndIsDeletedFalse(userId: String): Long = 0

        override fun countByUserIdAndRecordedDateAndIsDeletedFalse(
            userId: String,
            date: LocalDate,
        ): Long = 0

        override fun countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(
            userId: String,
            date: LocalDate,
        ): Long = 0

        override fun countByCategoryGrouped(userId: String): List<Array<Any>> = emptyList()

        override fun findMaxRecordedDate(userId: String): LocalDate? = null

        override fun findEngagementRanking(
            start: LocalDate,
            end: LocalDate,
            pageable: Pageable,
        ): List<Array<Any>> = emptyList()

        override fun countByUserAndPeriod(
            userId: String,
            start: LocalDate,
            end: LocalDate,
        ): Long = 0
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeUserTokenRepository :
        FakeJpaRepository<UserToken, Long>(),
        UserTokenRepository {
        private var seq = 0L

        override fun extractId(entity: UserToken): Long = entity.id

        override fun assignId(entity: UserToken): UserToken {
            if (entity.id == 0L) {
                val field = UserToken::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.set(entity, ++seq)
            }
            return entity
        }

        override fun findByUserIdAndCategoryId(
            userId: String,
            categoryId: Long,
        ): Optional<UserToken> =
            Optional.ofNullable(
                store.values.firstOrNull { it.user.id == userId && it.category.id == categoryId },
            )

        override fun findAllByUserId(userId: String): List<UserToken> = store.values.filter { it.user.id == userId }
    }

    private class FakeCategoryRepository :
        FakeJpaRepository<Category, Long>(),
        CategoryRepository {
        override fun extractId(entity: Category): Long = entity.id

        override fun findAllByIsActiveTrueAndIsCustomFalse(): List<Category> = store.values.filter { it.isActive && !it.isCustom }

        override fun findAllByOwnerUserIdAndIsActiveTrue(ownerUserId: String): List<Category> = store.values.filter { it.ownerUserId == ownerUserId && it.isActive }

        override fun countByOwnerUserIdAndIsActiveTrue(ownerUserId: String): Long = store.values.count { it.ownerUserId == ownerUserId && it.isActive }.toLong()

        override fun existsByNameAndOwnerUserIdAndIsActiveTrue(
            name: String,
            ownerUserId: String,
        ): Boolean = store.values.any { it.name == name && it.ownerUserId == ownerUserId && it.isActive }

        override fun existsByNameAndIsCustomFalse(name: String): Boolean = store.values.any { it.name == name && !it.isCustom }
    }
}
