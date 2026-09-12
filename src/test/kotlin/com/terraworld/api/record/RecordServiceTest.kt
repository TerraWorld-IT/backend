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
import com.terraworld.domain.record.DailyType
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.record.RecordStatisticsSummary
import com.terraworld.domain.record.UserMaxRecordedDateProjection
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import io.terraworld.api.model.CurrencyBalance
import io.terraworld.api.model.CurrencyResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.CALLS_REAL_METHODS
import org.mockito.Mockito.mock
import org.mockito.Mockito.mockStatic
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RecordService.createRecord 분기 커버 (낙서장 P1 read-cutover 후: 보상=CurrencyService.credit 단일 SoT).
 * - 해피: response.reward + record 저장 + credit(COIN/토큰) verify + updatedCurrency(신 substrate stub)
 * - dailyType=PHOTO: 코인 10 + 이슬 1 credit
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
    fun `createRecord dailyType=PHOTO — 코인 10 + 이슬(DEW) 1 credit`() {
        whenever(currencyService.currencyResponse(any())).thenReturn(CurrencyResponse(balances = emptyList()))

        val response =
            service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, dailyType = com.terraworld.domain.record.DailyType.PHOTO))

        assertEquals(10, response.reward.basicCoins)
        assertEquals(1, response.reward.categoryTokens)
        verify(currencyService).credit(eq("user-1"), eq("COIN"), eq(10L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("DEW"), eq(1L), any(), anyOrNull(), anyOrNull())
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

    // ─── getStatistics (ADD-BE-02: 조건부 집계 1쿼리) ───────────

    @Test
    fun `getStatistics — 조건부 집계 결과가 today,week,total 로 매핑된다`() {
        service.createRecord("user-1", CreateRecordRequest(categoryId = 1L))

        val stats = service.getStatistics("user-1")

        assertEquals(1L, stats.todayRecords)
        assertEquals(1L, stats.thisWeekRecords)
        assertEquals(1L, stats.totalRecords)
    }

    @Test
    fun `getStatistics — 기록 0건이면 전부 0 (SUM null 정규화)`() {
        val stats = service.getStatistics("user-1")

        assertEquals(0L, stats.todayRecords)
        assertEquals(0L, stats.thisWeekRecords)
        assertEquals(0L, stats.totalRecords)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    @Test
    fun `daily rewards — 각 타입 최초만 지급하고 삭제 후에도 cap까지 저장하며 초과는 거부`() {
        val names = listOf("산책", "독서", "러닝", "낙서")
        DailyType.entries.forEachIndexed { index, type ->
            val categoryId = index + 1L
            val limit = if (type == DailyType.FOCUS) 3 else 5
            categoryRepo.save(Category(id = categoryId, name = names[index], tokenName = "토큰", dailyLimit = limit))
            val request = CreateRecordRequest(categoryId = categoryId, dailyType = type)
            val first = service.createRecord("user-1", request)
            assertEquals(type.coinReward.toInt(), first.reward.basicCoins)
            assertEquals(type.tokenReward.toInt(), first.reward.categoryTokens)
            service.deleteRecord("user-1", first.record.id)
            repeat(limit - 1) {
                val next = service.createRecord("user-1", request)
                assertEquals(0, next.reward.basicCoins)
                assertEquals(0, next.reward.categoryTokens)
            }
            val error = assertThrows<BusinessException> { service.createRecord("user-1", request) }
            assertEquals(ErrorCode.DAILY_LIMIT_EXCEEDED, error.errorCode)
            verify(currencyService).credit(eq("user-1"), eq(type.currencyCode), eq(type.tokenReward), any(), anyOrNull(), anyOrNull())
        }
        assertEquals(18, recordRepo.all().size)
        assertEquals(4, recordRepo.all().count { it.rewardGranted })
        verify(currencyService, org.mockito.kotlin.times(8)).credit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `daily rewards — KST 자정 전후 최초 지급은 재개되고 각 날짜의 두번째는 0`() {
        var instant = Instant.parse("2026-09-12T14:59:59Z")
        mockStatic(LocalDate::class.java, CALLS_REAL_METHODS).use { dates ->
            dates.`when`<LocalDate> { LocalDate.now(any<ZoneId>()) }.thenAnswer {
                LocalDate.ofInstant(instant, it.getArgument<ZoneId>(0))
            }
            val request = CreateRecordRequest(categoryId = 1L, dailyType = DailyType.PHOTO)
            val first = service.createRecord("user-1", request)
            assertEquals(LocalDate.of(2026, 9, 12), first.record.recordedDate)
            assertEquals(1, first.reward.categoryTokens)
            assertEquals(0, service.createRecord("user-1", request).reward.categoryTokens)
            instant = Instant.parse("2026-09-12T15:00:00Z")
            val tomorrow = service.createRecord("user-1", request)
            assertEquals(LocalDate.of(2026, 9, 13), tomorrow.record.recordedDate)
            assertEquals(10, tomorrow.reward.basicCoins)
            assertEquals(1, tomorrow.reward.categoryTokens)
            assertEquals(0, service.createRecord("user-1", request).reward.categoryTokens)
        }
    }

    @Test
    fun `daily rewards — 공동 기록 상대의 최초 보상은 본인과 독립 판정`() {
        userRepo.save(User(id = "partner", nickname = "친구"))
        whenever(inviteRepo.existsAcceptedBetween("user-1", "partner")).thenReturn(true)
        service.createRecord("user-1", CreateRecordRequest(categoryId = 1L, dailyType = DailyType.PHOTO))
        val joint = CreateRecordRequest(categoryId = 1L, dailyType = DailyType.PHOTO, partnerUserId = "partner")
        assertEquals(0, service.createRecord("user-1", joint).reward.categoryTokens)
        assertEquals(0, service.createRecord("user-1", joint).reward.categoryTokens)
        verify(currencyService).credit(eq("partner"), eq("DEW"), eq(1L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("user-1"), eq("DEW"), eq(1L), any(), anyOrNull(), anyOrNull())
    }

    private class FakeRecordRepository :
        FakeJpaRepository<ActivityRecord, Long>(),
        RecordRepository {
        override fun existsByUserIdAndRecordedDateAndDailyType(
            userId: String,
            recordedDate: LocalDate,
            dailyType: DailyType,
        ): Boolean = store.values.any { it.user.id == userId && it.recordedDate == recordedDate && it.dailyType == dailyType }

        override fun existsByPhotoUrl(photoUrl: String): Boolean = store.values.any { it.photoUrl == photoUrl }

        override fun findPhotoUrlsByUserId(userId: String): List<String> = store.values.filter { it.user.id == userId }.mapNotNull { it.photoUrl }

        override fun findPhotoUrlsReferencedByOtherUsers(
            userId: String,
            photoUrls: Collection<String>,
        ): List<String> =
            store.values
                .filter { it.user.id != userId }
                .mapNotNull { it.photoUrl }
                .filter { it in photoUrls }
                .distinct()

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
                ?: store.values.count { it.user.id == userId && it.category.id == categoryId && it.recordedDate == recordedDate && !it.isDeleted }.toLong()

        // REC-DELETE-REFARM: 민팅 cap 은 soft-delete 포함 전건 카운트 (재민팅 차단).
        override fun countByUserIdAndRecordedDateAndCategoryId(
            userId: String,
            recordedDate: LocalDate,
            categoryId: Long,
        ): Long =
            todayCountOverride
                ?: store.values.count { it.user.id == userId && it.category.id == categoryId && it.recordedDate == recordedDate }.toLong()

        override fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            pageable: Pageable,
        ): Page<ActivityRecord> = PageImpl(store.values.filter { it.user.id == userId && !it.isDeleted }.sortedByDescending { it.createdAt })

        // BE-16: 월 조회 pageable 계약 정합 — Page 반환 (테스트는 Pageable.unpaged() 로 전건 확인)
        override fun findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            from: LocalDate,
            to: LocalDate,
            pageable: Pageable,
        ): Page<ActivityRecord> =
            PageImpl(
                store.values
                    .filter {
                        it.user.id == userId && !it.isDeleted && !it.recordedDate.isBefore(from) && !it.recordedDate.isAfter(to)
                    }.sortedByDescending { it.createdAt },
            )

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

        override fun findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(
            userId: String,
            categoryId: Long,
            from: LocalDate,
            to: LocalDate,
            pageable: Pageable,
        ): Page<ActivityRecord> =
            PageImpl(
                store.values
                    .filter {
                        it.user.id == userId &&
                            it.category.id == categoryId &&
                            !it.isDeleted &&
                            !it.recordedDate.isBefore(from) &&
                            !it.recordedDate.isAfter(to)
                    }.sortedByDescending { it.createdAt },
            )

        override fun acquireRecordDailyLock(key: String): Int = 1

        // 아프젝 v2: 친구 스코프 engagement 랭킹 — 본 테스트 비대상
        override fun findEngagementRankingAmong(
            userIds: Collection<String>,
            start: LocalDate,
            end: LocalDate,
            pageable: org.springframework.data.domain.Pageable,
        ): List<Array<Any>> = emptyList()

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

        // BE-05: 배치 projection — store 기반 실동작 (스케줄러 경로용)
        override fun findMaxRecordedDatePerUser(): List<UserMaxRecordedDateProjection> =
            store.values
                .filter { !it.isDeleted }
                .groupBy { it.user.id }
                .map { (uid, records) ->
                    object : UserMaxRecordedDateProjection {
                        override val userId = uid
                        override val maxRecordedDate = records.maxOf { it.recordedDate }
                    }
                }

        // ADD-BE-02: 조건부 집계 1쿼리 — JPQL 시맨틱 모사 (row 0건 시 SUM=null, COUNT=0)
        override fun countStatisticsSummary(
            userId: String,
            today: LocalDate,
            weekAgo: LocalDate,
        ): RecordStatisticsSummary {
            val mine = store.values.filter { it.user.id == userId && !it.isDeleted }
            return object : RecordStatisticsSummary {
                override val todayCount = if (mine.isEmpty()) null else mine.count { it.recordedDate == today }.toLong()
                override val weekCount = if (mine.isEmpty()) null else mine.count { !it.recordedDate.isBefore(weekAgo) }.toLong()
                override val totalCount = mine.size.toLong()
            }
        }

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

        override fun acquireBootstrapLock(key: String): Int = 1
    }

    private class FakeCategoryRepository :
        FakeJpaRepository<Category, Long>(),
        CategoryRepository {
        override fun releaseSharedCategories(userId: String): Int = error("계정 삭제는 실제 DB 통합 테스트로 검증")

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
