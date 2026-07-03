package com.terraworld.api.user

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import io.terraworld.api.model.UserMeResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UserService.getMe 분기 커버 (낙서장 P1: 레벨/EXP·progress 제거 후).
 * - getMe 해피 패스: 소유아이템 / entitlement 매핑
 * - role enum 매핑: domain UserRole.ADMIN → generated UserMeResponse.Role.ADMIN
 * - 사용자 미존재 → USER_NOT_FOUND
 */
class UserServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var userItemRepo: FakeUserItemRepository
    private lateinit var terrariumRepo: TerrariumRepository
    private lateinit var entitlementService: com.terraworld.api.entitlement.EntitlementService
    private lateinit var service: UserService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        userItemRepo = FakeUserItemRepository()
        terrariumRepo = Mockito.mock(TerrariumRepository::class.java)
        entitlementService = Mockito.mock(com.terraworld.api.entitlement.EntitlementService::class.java)

        val walletBuilder =
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

        service =
            UserService(
                userRepo,
                userItemRepo,
                terrariumRepo,
                walletBuilder,
                entitlementService,
            )

        // 기본: terrarium 없음 (placedItems 빈 목록)
        Mockito.`when`(terrariumRepo.findByUserId(Mockito.anyString())).thenReturn(Optional.empty())
    }

    @Test
    fun `getMe 해피 패스 — 소유아이템·entitlement 매핑`() {
        val user = User(id = "user-1", nickname = "테스터", role = UserRole.USER)
        userRepo.save(user)

        // 보유 아이템 1개 (slug = "cactus")
        val cactus =
            Item(id = 1L, slug = "cactus", name = "선인장", priceType = PriceType.BASIC, priceAmount = 100, assetUrl = "https://x/c.png")
        userItemRepo.save(UserItem(id = 1L, user = user, item = cactus))

        Mockito.`when`(entitlementService.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT)).thenReturn(true)
        Mockito.`when`(entitlementService.hasEntitlement("user-1", UserEntitlementId.PREMIUM_THEMES)).thenReturn(false)

        val response = service.getMe("user-1", "tester@example.com")

        assertEquals("user-1", response.userId)
        assertEquals("tester@example.com", response.email)
        assertEquals("테스터", response.nickname)
        assertEquals(UserMeResponse.Role.USER, response.role)
        assertEquals(listOf("cactus"), response.ownedItems)
        assertTrue(response.placedItems.isEmpty())
        assertEquals(true, response.entitlements?.freePlacement)
        assertEquals(false, response.entitlements?.premiumThemes)
    }

    @Test
    fun `getMe — ADMIN 역할은 generated Role ADMIN 으로 매핑`() {
        val admin = User(id = "admin-1", nickname = "관리자", role = UserRole.ADMIN)
        userRepo.save(admin)

        val response = service.getMe("admin-1", "admin@example.com")

        assertEquals(UserMeResponse.Role.ADMIN, response.role)
    }

    @Test
    fun `getMe — 존재하지 않는 사용자는 USER_NOT_FOUND`() {
        val ex =
            assertThrows<BusinessException> {
                service.getMe("ghost", "ghost@example.com")
            }
        assertEquals(ErrorCode.USER_NOT_FOUND, ex.errorCode)
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeUserRepository :
        com.terraworld.test.FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeUserItemRepository :
        com.terraworld.test.FakeJpaRepository<UserItem, Long>(),
        UserItemRepository {
        override fun extractId(entity: UserItem): Long = entity.id

        override fun findAllByUserId(userId: String): List<UserItem> = store.values.filter { it.user.id == userId }

        override fun existsByUserIdAndItemId(
            userId: String,
            itemId: Long,
        ): Boolean = store.values.any { it.user.id == userId && it.item.id == itemId }
    }
}
