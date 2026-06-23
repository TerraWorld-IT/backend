package com.terraworld.api.purchase

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
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Optional
import kotlin.test.assertEquals

/**
 * PurchaseService 의 priceType 4 분기 + 에러 분기 커버.
 *
 * - BASIC / SPECIAL / TOKEN / MIXED 각 해피 패스
 * - 사용자 / 아이템 미존재
 * - ALREADY_OWNED
 * - 잔고 부족 (4 분기 각각)
 */
class PurchaseServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var itemRepo: FakeItemRepository
    private lateinit var userItemRepo: FakeUserItemRepository
    private lateinit var service: PurchaseService

    private lateinit var walkCategory: Category
    private lateinit var user: User

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        tokenRepo = FakeUserTokenRepository()
        itemRepo = FakeItemRepository()
        userItemRepo = FakeUserItemRepository()

        val walletBuilder = WalletBuilder(tokenRepo)
        val auditService = org.mockito.Mockito.mock(AuditService::class.java)
        // N2 (구현 계획서 v4): PurchaseService 생성자에 walletTransactionService 추가됨 (구매 ledger).
        // 본 test 는 ledger 경로를 검증하지 않으므로 mock — append() 반환값 미사용.
        val walletTransactionService =
            org.mockito.Mockito.mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        service =
            PurchaseService(
                userRepo,
                tokenRepo,
                itemRepo,
                userItemRepo,
                walletBuilder,
                auditService,
                walletTransactionService,
            )

        walkCategory = Category(id = 1L, name = "산책", tokenName = "산책토큰")
        user = User(id = "user-1", nickname = "테스터", basicCoin = 100, specialCoin = 30)
        userRepo.save(user)
        tokenRepo.save(UserToken(id = 1L, user = user, category = walkCategory, amount = 50))
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

    // ─── 해피 패스 4 분기 ──────────────────────────────────────

    @Test
    fun `BASIC 가격 — basicCoin 차감`() {
        saveItem(1L, "tree-basic", "기본 나무", PriceType.BASIC, 30)

        val response = service.purchase("user-1", PurchaseRequest(itemId = 1L))

        assertEquals("tree-basic", response.purchasedItem.slug)
        assertEquals(70, userRepo.findById("user-1").get().basicCoin)
        assertEquals(1, userItemRepo.all().size)
    }

    @Test
    fun `SPECIAL 가격 — specialCoin 차감`() {
        saveItem(2L, "rare-tree", "스페셜 나무", PriceType.SPECIAL, 10)

        service.purchase("user-1", PurchaseRequest(itemId = 2L))

        assertEquals(20, userRepo.findById("user-1").get().specialCoin)
    }

    @Test
    fun `TOKEN 가격 — 카테고리 토큰만 차감`() {
        saveItem(3L, "walk-stone", "산책 돌", PriceType.TOKEN, priceAmount = 20, category = walkCategory)

        service.purchase("user-1", PurchaseRequest(itemId = 3L))

        assertEquals(30, tokenRepo.findByUserIdAndCategoryId("user-1", 1L).get().amount)
        // basic / special 은 변경 없음
        assertEquals(100, userRepo.findById("user-1").get().basicCoin)
    }

    @Test
    fun `MIXED 가격 — basicCoin + 카테고리 토큰 동시 차감`() {
        saveItem(
            4L,
            "mixed-arch",
            "혼합 아치",
            PriceType.MIXED,
            priceAmount = 40,
            tokenPrice = 15,
            category = walkCategory,
        )

        service.purchase("user-1", PurchaseRequest(itemId = 4L))

        assertEquals(60, userRepo.findById("user-1").get().basicCoin)
        assertEquals(35, tokenRepo.findByUserIdAndCategoryId("user-1", 1L).get().amount)
    }

    // ─── 에러 분기 ─────────────────────────────────────────────

    @Test
    fun `존재하지 않는 사용자 → USER_NOT_FOUND`() {
        saveItem(10L, "x", "x", PriceType.BASIC, 1)

        val ex =
            assertThrows<BusinessException> {
                service.purchase("ghost", PurchaseRequest(itemId = 10L))
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `존재하지 않는 아이템 → ITEM_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 999L))
            }
        assertEquals(ErrorCode.ITEM_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `이미 보유한 아이템 → ALREADY_OWNED`() {
        val item = saveItem(11L, "owned", "이미 보유", PriceType.BASIC, 10)
        userItemRepo.save(UserItem(id = 1L, user = user, item = item))

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 11L))
            }
        assertEquals(ErrorCode.ALREADY_OWNED, ex.errorCode)
    }

    @Test
    fun `BASIC 잔고 부족 → INSUFFICIENT_FUNDS`() {
        saveItem(20L, "expensive", "비싼", PriceType.BASIC, 9999)

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 20L))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `SPECIAL 잔고 부족 → INSUFFICIENT_FUNDS`() {
        saveItem(21L, "expensive-special", "비싼 스페셜", PriceType.SPECIAL, 9999)

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 21L))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `TOKEN 잔고 부족 → INSUFFICIENT_FUNDS`() {
        saveItem(
            22L,
            "expensive-token",
            "비싼 토큰",
            PriceType.TOKEN,
            priceAmount = 9999,
            category = walkCategory,
        )

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 22L))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `MIXED basicCoin 잔고 부족 → INSUFFICIENT_FUNDS`() {
        saveItem(
            23L,
            "mixed-too-much-coin",
            "혼합 코인 부족",
            PriceType.MIXED,
            priceAmount = 9999,
            tokenPrice = 1,
            category = walkCategory,
        )

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 23L))
            }
        assertEquals(ErrorCode.INSUFFICIENT_FUNDS, ex.errorCode)
    }

    @Test
    fun `MIXED token 잔고 부족 → INSUFFICIENT_FUNDS`() {
        saveItem(
            24L,
            "mixed-too-much-token",
            "혼합 토큰 부족",
            PriceType.MIXED,
            priceAmount = 1,
            tokenPrice = 9999,
            category = walkCategory,
        )

        val ex =
            assertThrows<BusinessException> {
                service.purchase("user-1", PurchaseRequest(itemId = 24L))
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
    }
}
