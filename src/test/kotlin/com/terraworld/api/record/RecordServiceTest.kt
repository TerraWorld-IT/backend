package com.terraworld.api.record

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.record.dto.CreateRecordRequest
import com.terraworld.api.user.WalletBuilder
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
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RecordService.createRecord 분기 커버 (낙서장 P1 read-cutover 후: 보상=CurrencyService.credit 단일 SoT).
 * - 해피: response.reward + record 저장 + credit(COIN/토큰) verify + updatedCurrency(신 substrate stub)
 * - dailyType=PHOTO: 코인 10 + 이슬 2 credit
 * - dailyLimit / USER_NOT_FOUND / CATEGORY_NOT_FOUND / partner INVALID / co-record / duration
 */
class RecordServiceTest {
    private lateinit var recordRepo: FakeRecordRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var categoryRepo: FakeCategoryRepository
    private lateinit var inviteRepo: InviteRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var growthService: com.terraworld.api.growth.GrowthService
    private lateinit var service: RecordService

    private lateinit var walkCategory: Category

    @BeforeEach
    fun setup() {
        recordRepo = FakeRecordRepository()
        userRepo = FakeUserRepository()
        categoryRepo = FakeCategoryRepository()
        inviteRepo = mock(InviteRepository::class.java)
        currencyService = mock(CurrencyService::class.java)
        growthService = mock(com.terraworld.api.growth.GrowthService::class.java)
        whenever(currencyService.currencyResponse(any())).thenReturn(
            CurrencyResponse(
                balances =
                    listOf(
                        CurrencyBalance(code = "COIN", amount = 20),
                        CurrencyBalance(code = "DEW", amount = 10),
                    ),
            ),
        )
        val walletBuilder = WalletBuilder(currencyService)

        service =
            RecordService(
                recordRepo,
                userRepo,
                currencyService,
                categoryRepo,
                inviteRepo,
                walletBuilder,
                mock(WalletTransactionService::class.java),
                growthService,
            )

        userRepo.save(User(id = "user-1", nickname = "테스터"))
        walkCategory = Category(id = 1L, name = "산책", tokenName = "산책토큰", baseCoinReward = 20, baseTokenReward = 10, dailyLimit = 5)
        categoryRepo.save(walkCategory)
    }

    @Test
    fun `createRecord 해피 패스 — reward 20+10 + record 저장 + credit(COIN 20, DEW 10) + 육성 진행`() {
        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))

        assertEquals(20, response.reward.basicCoins)
        assertEquals(10, response.reward.categoryTokens)
        assertEquals(1L, response.record.categoryId)
        assertEquals("산책", response.record.categoryName)
        assertEquals(1, recordRepo.all().size)
        // 신 substrate credit (COIN 20 + DEW 10 — category1→DEW)
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(20L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("DEW"), eq(10L), any(), anyOrNull(), anyOrNull())
        // updatedCurrency 는 신 substrate(currencyResponse stub)
        assertEquals(
            20L,
            response.updatedCurrency.balances
                .first { it.code == "COIN" }
                .amount,
        )
        // 낙서장 P3: 기록 → 육성 진행
        verify(growthService).advanceAllStreaks(eq("user-1"))
    }

    @Test
    fun `createRecord 커스텀 카테고리 — 토큰분을 COIN 으로 지급 + categoryTokens=0 정직 표기 `() {
        // 커스텀 카테고리(id=5, 원소 토큰 없음): baseCoin 15 + baseToken 8 → 전부 COIN, 응답 token=0
        val custom =
            Category(id = 5L, name = "커스텀", tokenName = "커스텀토큰", baseCoinReward = 15, baseTokenReward = 8, dailyLimit = 5, isCustom = true, ownerUserId = "user-1")
        categoryRepo.save(custom)
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))

        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 5L))

        assertEquals(23, response.reward.basicCoins) // 15 + 8 (토큰분 fold)
        assertEquals(0, response.reward.categoryTokens) // 원소 토큰 없음 → 0 (divergence 차단)
        // COIN 2회 credit (baseCoin 15 + token-fold 8) — 원소 토큰 credit 없음
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(15L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(8L), any(), anyOrNull(), anyOrNull())
        verify(currencyService, org.mockito.kotlin.never()).credit(eq("user-1"), eq("DEW"), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `createRecord dailyType=PHOTO — 코인 10 + 이슬(DEW) 2 credit`() {
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))

        val response =
            service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, dailyType = com.terraworld.domain.record.DailyType.PHOTO))

        assertEquals(10, response.reward.basicCoins)
        assertEquals(2, response.reward.categoryTokens)
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(10L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("DEW"), eq(2L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `createRecord — dailyLimit 도달 시 DAILY_LIMIT_EXCEEDED (보상·기록 없음)`() {
        recordRepo.todayCountOverride = 5
        val ex = assertThrows<BusinessException> { service.createRecord("user-1", CreateRecordRequest(categoryId = 1L)) }
        assertEquals(ErrorCode.DAILY_LIMIT_EXCEEDED, ex.errorCode)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — dailyLimit 직전(limit-1)은 정상 통과`() {
        recordRepo.todayCountOverride = 4
        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))
        assertEquals(20, response.reward.basicCoins)
        assertEquals(1, recordRepo.all().size)
    }

    @Test
    fun `createRecord — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex = assertThrows<BusinessException> { service.createRecord("ghost", CreateRecordRequest(categoryId = 1L)) }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `createRecord — 존재하지 않는 카테고리는 CATEGORY_NOT_FOUND`() {
        val ex = assertThrows<BusinessException> { service.createRecord("user-1", CreateRecordRequest(categoryId = 999L)) }
        assertEquals(ErrorCode.CATEGORY_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `createRecord — 본인을 partner 로 지정하면 INVALID_PARTNER (기록 없음)`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, partnerUserId = "user-1"))
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 존재하지 않는 partner 는 INVALID_PARTNER`() {
        val ex =
            assertThrows<BusinessException> {
                service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, partnerUserId = "ghost"))
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 친구 아닌 partner 는 INVALID_PARTNER`() {
        userRepo.save(User(id = "partner-1", nickname = "짝꿍"))
        whenever(inviteRepo.existsAcceptedBetween("user-1", "partner-1")).thenReturn(false)
        val ex =
            assertThrows<BusinessException> {
                service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, partnerUserId = "partner-1"))
            }
        assertEquals(ErrorCode.INVALID_PARTNER, ex.errorCode)
        assertTrue(recordRepo.all().isEmpty())
    }

    @Test
    fun `createRecord — 친구 co-record 는 양쪽 record 저장 + partner 보상·육성 진행`() {
        userRepo.save(User(id = "partner-1", nickname = "짝꿍"))
        whenever(inviteRepo.existsAcceptedBetween("user-1", "partner-1")).thenReturn(true)

        val response = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, partnerUserId = "partner-1"))

        val all = recordRepo.all()
        assertEquals(2, all.size)
        val ownRecord = all.first { it.user.id == "user-1" }
        assertEquals("partner-1", ownRecord.partnerUserId)
        assertTrue(ownRecord.jointSessionId != null)
        val partnerRecord = all.first { it.user.id == "partner-1" }
        assertEquals("user-1", partnerRecord.partnerUserId)
        assertEquals(ownRecord.jointSessionId, partnerRecord.jointSessionId)
        // 양측 보상 credit + 양측 육성 진행 (리뷰 Q#5)
        verify(currencyService).credit(eq("partner-1"), eq("COIN"), eq(20L), any(), anyOrNull(), anyOrNull())
        verify(growthService).advanceAllStreaks(eq("user-1"))
        verify(growthService).advanceAllStreaks(eq("partner-1"))
        assertEquals(20, response.reward.basicCoins)
    }

    @Test
    fun `createRecord — duration 영속화 + reload(toResponse) 유지 (P0-1 silent-drop fix)`() {
        val created = service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, duration = 30))
        assertEquals(30, created.record.duration)
        val reloaded = service.getRecords("user-1", null, null, null, Pageable.unpaged())
        assertEquals(1, reloaded.content.size)
        assertEquals(30, reloaded.content.first().duration)
        val today =
            com.terraworld.common.time.KstTime
                .today()
        val monthly = service.getRecords("user-1", null, today.year, today.monthValue, Pageable.unpaged())
        assertEquals(30, monthly.content.first().duration)
    }

    @Test
    fun `createRecord — duration 미입력 시 null 로 저장되고 reload 시에도 null`() {
        service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))
        val reloaded = service.getRecords("user-1", null, null, null, Pageable.unpaged())
        assertEquals(null, reloaded.content.first().duration)
    }

    // ─── Fakes ─────────────────────────────────────────────────

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
                ?: store.values.count { it.user.id == userId && it.category.id == categoryId && !it.isDeleted }.toLong()

        // REC-DELETE-REFARM: 민팅 cap 은 soft-delete 포함 전건 카운트 (재민팅 차단).
        override fun countByUserIdAndRecordedDateAndCategoryId(
            userId: String,
            recordedDate: LocalDate,
            categoryId: Long,
        ): Long =
            todayCountOverride
                ?: store.values.count { it.user.id == userId && it.category.id == categoryId }.toLong()

        override fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            pageable: Pageable,
        ): Page<ActivityRecord> = PageImpl(store.values.filter { it.user.id == userId && !it.isDeleted }.sortedByDescending { it.createdAt })

        override fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(
            userId: String,
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityRecord> =
            store.values.filter {
                it.user.id == userId && !it.isDeleted && !it.recordedDate.isBefore(from) && !it.recordedDate.isAfter(to)
            }

        override fun findAllByUserIdAndCategoryIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            categoryId: Long,
            pageable: Pageable,
        ): Page<ActivityRecord> =
            PageImpl(
                store.values
                    .filter { it.user.id == userId && it.category.id == categoryId && !it.isDeleted }
                    .sortedByDescending { it.createdAt },
            )

        override fun findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalse(
            userId: String,
            categoryId: Long,
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityRecord> =
            store.values.filter {
                it.user.id == userId &&
                    it.category.id == categoryId &&
                    !it.isDeleted &&
                    !it.recordedDate.isBefore(from) &&
                    !it.recordedDate.isAfter(to)
            }

        override fun acquireRecordDailyLock(key: String): Int = 1

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
