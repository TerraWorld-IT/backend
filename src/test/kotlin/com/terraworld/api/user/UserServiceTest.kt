package com.terraworld.api.user

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfig
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.UserMeResponse
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UserService.getMe 분기 커버.
 * - getMe 해피 패스: progress / ownedItems / entitlements 매핑 + expToNext 계산
 * - role enum 매핑: domain UserRole.ADMIN → generated UserMeResponse.Role.ADMIN (mapRoleOrFallback)
 * - expToNext clamp: totalExp > requiredExp 시 0 (V14 linear curve auto-level-up 대기 상태)
 * - 다음 레벨 config 부재 시 expToNext = 0 (만렙)
 * - 사용자 미존재 → USER_NOT_FOUND
 *
 * 본 서비스에는 updateMe / 닉네임 검증 메서드가 없다 (public 메서드는 getMe 단일).
 * role forValue fallback(ADR-019 warn) 은 domain UserRole(USER/ADMIN) 가 generated
 * Role(USER/ADMIN) 와 1:1 이므로 정상 매핑 경로로만 도달 가능 — drift 값은 enum 타입상 주입 불가.
 */
class UserServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var tokenRepo: FakeUserTokenRepository
    private lateinit var userItemRepo: FakeUserItemRepository
    private lateinit var levelConfigRepo: FakeLevelConfigRepository
    private lateinit var terrariumRepo: TerrariumRepository
    private lateinit var entitlementService: com.terraworld.api.entitlement.EntitlementService
    private lateinit var service: UserService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        tokenRepo = FakeUserTokenRepository()
        userItemRepo = FakeUserItemRepository()
        levelConfigRepo = FakeLevelConfigRepository()
        terrariumRepo = Mockito.mock(TerrariumRepository::class.java)
        entitlementService = Mockito.mock(com.terraworld.api.entitlement.EntitlementService::class.java)

        val walletBuilder = WalletBuilder(tokenRepo)

        service =
            UserService(
                userRepo,
                tokenRepo,
                userItemRepo,
                terrariumRepo,
                levelConfigRepo,
                walletBuilder,
                entitlementService,
            )

        // 기본: terrarium 없음 (placedItems 빈 목록)
        Mockito.`when`(terrariumRepo.findByUserId(Mockito.anyString())).thenReturn(Optional.empty())
    }

    @Test
    fun `getMe 해피 패스 — progress·소유아이템·entitlement 매핑`() {
        val user = User(id = "user-1", nickname = "테스터", role = UserRole.USER, level = 2, totalExp = 150)
        userRepo.save(user)

        // 다음 레벨(3) requiredExp = 300 → expToNext = 150
        levelConfigRepo.save(LevelConfig(level = 3, requiredExp = 300))

        // 보유 아이템 1개 (slug = "cactus")
        val cactus = Item(id = 1L, slug = "cactus", name = "선인장", priceType = PriceType.BASIC, priceAmount = 100, assetUrl = "https://x/c.png")
        userItemRepo.save(UserItem(id = 1L, user = user, item = cactus))

        Mockito.`when`(entitlementService.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT)).thenReturn(true)
        Mockito.`when`(entitlementService.hasEntitlement("user-1", UserEntitlementId.PREMIUM_THEMES)).thenReturn(false)

        val response = service.getMe("user-1", "tester@example.com")

        assertEquals("user-1", response.userId)
        assertEquals("tester@example.com", response.email)
        assertEquals("테스터", response.nickname)
        assertEquals(UserMeResponse.Role.USER, response.role)
        assertEquals(2, response.progress.level)
        assertEquals(150L, response.progress.experience)
        assertEquals(150L, response.progress.experienceToNext)
        assertEquals(listOf("cactus"), response.ownedItems)
        assertTrue(response.placedItems.isEmpty())
        assertEquals(true, response.entitlements?.freePlacement)
        assertEquals(false, response.entitlements?.premiumThemes)
    }

    @Test
    fun `getMe — ADMIN 역할은 generated Role ADMIN 으로 매핑`() {
        val admin = User(id = "admin-1", nickname = "관리자", role = UserRole.ADMIN, level = 1, totalExp = 0)
        userRepo.save(admin)
        levelConfigRepo.save(LevelConfig(level = 2, requiredExp = 100))

        val response = service.getMe("admin-1", "admin@example.com")

        assertEquals(UserMeResponse.Role.ADMIN, response.role)
    }

    @Test
    fun `getMe — totalExp 가 다음 requiredExp 보다 크면 expToNext 는 0 으로 clamp`() {
        // V14 linear curve: auto-level-up 대기 상태에서 음수 방지 clamp 검증
        val user = User(id = "user-2", nickname = "초과", role = UserRole.USER, level = 2, totalExp = 500)
        userRepo.save(user)
        levelConfigRepo.save(LevelConfig(level = 3, requiredExp = 300))

        val response = service.getMe("user-2", "over@example.com")

        assertEquals(0L, response.progress.experienceToNext)
    }

    @Test
    fun `getMe — 다음 레벨 config 가 없으면 expToNext 는 0 (만렙)`() {
        val user = User(id = "user-3", nickname = "만렙", role = UserRole.USER, level = 10, totalExp = 9000)
        userRepo.save(user)
        // level 11 config 미등록

        val response = service.getMe("user-3", "max@example.com")

        assertEquals(0L, response.progress.experienceToNext)
        assertTrue(response.ownedItems.isEmpty())
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

    private class FakeUserTokenRepository :
        com.terraworld.test.FakeJpaRepository<UserToken, Long>(),
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

    private class FakeLevelConfigRepository :
        com.terraworld.test.FakeJpaRepository<LevelConfig, Int>(),
        LevelConfigRepository {
        override fun extractId(entity: LevelConfig): Int = entity.level

        override fun findByLevel(level: Int): Optional<LevelConfig> = Optional.ofNullable(store[level])
    }
}
