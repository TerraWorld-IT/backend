package com.terraworld.api.purchase

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.purchase.dto.PurchaseRequest
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.Rarity
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import kotlin.test.assertEquals

/**
 * PurchaseService priceType 4 분기 + 에러 (낙서장 P1 read-cutover 후: 차감=CurrencyService.debit 단일 SoT).
 * - BASIC/SPECIAL/TOKEN/MIXED 해피: debit(해당 화폐) verify + userItem 저장
 * - 미존재/ALREADY_OWNED / debit throw(INSUFFICIENT_FUNDS) 전파
 */
class PurchaseServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var itemRepo: FakeItemRepository
    private lateinit var userItemRepo: FakeUserItemRepository
    private lateinit var currencyService: CurrencyService
    private lateinit var service: PurchaseService

    private lateinit var walkCategory: Category
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        itemRepo = FakeItemRepository()
        userItemRepo = FakeUserItemRepository()
        currencyService = mock(CurrencyService::class.java)
        whenever(currencyService.currencyResponse(any())).thenReturn(
            io.terraworld.api.model
                .CurrencyResponse(balances = emptyList()),
        )

        val walletBuilder = WalletBuilder(currencyService)
        val auditService = mock(AuditService::class.java)
        val walletTransactionService = mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        service =
            PurchaseService(
                userRepo,
                itemRepo,
                userItemRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
                currencyService,
            )

        walkCategory = Category(id = 1L, name = "산책", tokenName = "산책토큰")
        user = User(id = "user-1", nickname = "테스터")
        userRepo.save(user)
    }

    private fun saveItem(
        id: Long,
        slug: String,
        name: String,
        priceType: PriceType,
        priceAmount: Int,
        tokenPrice: Int? = null,
        category: Category? = null,
    ): Item {
        val item =
            Item(
                id = id,
                slug = slug,
                name = name,
                priceType = priceType,
                priceAmount = priceAmount,
                tokenPrice = tokenPrice,
                category = category,
                assetUrl = "/$slug.png",
                rarity = Rarity.COMMON,
                layout = ItemLayout.FOREGROUND,
            )
        itemRepo.save(item)
        return item
    }

    @Test
    fun `BASIC 가격 — COIN debit`() {
        saveItem(1L, "tree-basic", "기본 나무", PriceType.BASIC, 30)
        val response = service.purchase("user-1", PurchaseRequest(itemId = 1L))
        assertEquals("tree-basic", response.purchasedItem.slug)
        verify(currencyService).debit(eq("user-1"), eq("COIN"), eq(30L), any(), anyOrNull(), anyOrNull())
        assertEquals(1, userItemRepo.all().size)
    }

    @Test
    fun `SPECIAL 가격 — RUBY debit`() {
        saveItem(2L, "rare-tree", "스페셜 나무", PriceType.SPECIAL, 10)
        service.purchase("user-1", PurchaseRequest(itemId = 2L))
        verify(currencyService).debit(eq("user-1"), eq("RUBY"), eq(10L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `TOKEN 가격 — 카테고리 토큰(DEW) debit`() {
        saveItem(3L, "walk-stone", "산책 돌", PriceType.TOKEN, priceAmount = 20, category = walkCategory)
        service.purchase("user-1", PurchaseRequest(itemId = 3L))
        verify(currencyService).debit(eq("user-1"), eq("DEW"), eq(20L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `MIXED 가격 — COIN + 카테고리 토큰(DEW) 동시 debit`() {
        saveItem(4L, "mixed-arch", "혼합 아치", PriceType.MIXED, priceAmount = 40, tokenPrice = 15, category = walkCategory)
        service.purchase("user-1", PurchaseRequest(itemId = 4L))
        verify(currencyService).debit(eq("user-1"), eq("COIN"), eq(40L), any(), anyOrNull(), anyOrNull())
        verify(currencyService).debit(eq("user-1"), eq("DEW"), eq(15L), any(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `존재하지 않는 사용자 → USER_NOT_FOUND`() {
        saveItem(10L, "x", "x", PriceType.BASIC, 1)
        val ex = assertThrows<BusinessException> { service.purchase("ghost", PurchaseRequest(itemId = 10L)) }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `존재하지 않는 아이템 → ITEM_NOT_FOUND`() {
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 999L)) }
        assertEquals(ErrorCode.ITEM_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `이미 보유한 아이템 → ALREADY_OWNED`() {
        val item = saveItem(11L, "owned", "이미 보유", PriceType.BASIC, 10)
        userItemRepo.save(UserItem(id = 1L, user = user, item = item))
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 11L)) }
        assertEquals(ErrorCode.ALREADY_OWNED, ex.errorCode)
    }

    @Test
    fun `잔고 부족(debit INSUFFICIENT_FUNDS) 전파 + userItem 미저장`() {
        saveItem(20L, "expensive", "비싼", PriceType.BASIC, 9999)
        whenever(currencyService.debit(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 20L)) }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
        assertEquals(0, userItemRepo.all().size)
    }

    @Test
    fun `TOKEN 잔고 부족(debit throw) 전파`() {
        saveItem(22L, "expensive-token", "비싼 토큰", PriceType.TOKEN, priceAmount = 9999, category = walkCategory)
        whenever(currencyService.debit(any(), any(), any(), any(), anyOrNull(), anyOrNull()))
            .thenThrow(BusinessException(ErrorCode.INSUFFICIENT_FUNDS))
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 22L)) }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `TOKEN 아이템 category 누락 → INTERNAL_ERROR + debit 없음 + 무료 지급 차단 `() {
        saveItem(30L, "free-token", "카테고리 없는 토큰", PriceType.TOKEN, priceAmount = 20, category = null)
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 30L)) }
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.errorCode)
        verify(currencyService, org.mockito.kotlin.never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertEquals(0, userItemRepo.all().size) // 무료 지급 안 됨
    }

    @Test
    fun `TOKEN 아이템 커스텀 카테고리(원소 토큰 없음) → INTERNAL_ERROR (silent COIN 청구 차단)`() {
        val customCategory = Category(id = 5L, name = "커스텀", tokenName = "커스텀토큰")
        saveItem(31L, "custom-token", "커스텀 토큰가", PriceType.TOKEN, priceAmount = 20, category = customCategory)
        val ex = assertThrows<BusinessException> { service.purchase("user-1", PurchaseRequest(itemId = 31L)) }
        assertEquals(ErrorCode.INTERNAL_ERROR, ex.errorCode)
        verify(currencyService, org.mockito.kotlin.never()).debit(any(), any(), any(), any(), anyOrNull(), anyOrNull())
        assertEquals(0, userItemRepo.all().size)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeItemRepository :
        FakeJpaRepository<Item, Long>(),
        ItemRepository {
        override fun extractId(entity: Item): Long = entity.id

        override fun findAllByIsActiveTrue(): List<Item> = store.values.filter { it.isActive }

        override fun findAllByIsActiveTrueAndCategoryId(categoryId: Long): List<Item> = store.values.filter { it.isActive && it.category?.id == categoryId }

        override fun findAllByIsActiveTrueAndRarity(rarity: Rarity): List<Item> = store.values.filter { it.isActive && it.rarity == rarity }

        override fun findAllByIsActiveTrueAndLayout(layout: ItemLayout): List<Item> = store.values.filter { it.isActive && it.layout == layout }

        override fun findBySlug(slug: String): Optional<Item> = Optional.ofNullable(store.values.firstOrNull { it.slug == slug })
    }

    private class FakeUserItemRepository :
        FakeJpaRepository<UserItem, Long>(),
        UserItemRepository {
        private var nextId = 1L

        override fun extractId(entity: UserItem): Long = entity.id

        override fun assignId(entity: UserItem): UserItem {
            if (entity.id != 0L) return entity
            val idField = UserItem::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
        }

        override fun findAllByUserId(userId: String): List<UserItem> = store.values.filter { it.user.id == userId }

        override fun existsByUserIdAndItemId(
            userId: String,
            itemId: Long,
        ): Boolean = store.values.any { it.user.id == userId && it.item.id == itemId }

        // BE-03: TerrariumService.updatePlacements 일괄 소유 검증용
        override fun findOwnedItemIds(
            userId: String,
            itemIds: Collection<Long>,
        ): Set<Long> =
            store.values
                .filter { it.user.id == userId && it.item.id in itemIds }
                .map { it.item.id }
                .toSet()
    }
}
