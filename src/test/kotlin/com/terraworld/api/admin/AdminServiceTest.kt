package com.terraworld.api.admin

import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.Optional
import kotlin.test.assertEquals

/**
 * code-review CDX-004 (구현 계획서 v4, 2026-05-21): AdminService 검증/해피패스 커버.
 * Mockito 기반 — repository 는 mock, 입력 검증 (require) + entity mutation + save 호출 확인.
 */
class AdminServiceTest {
    private lateinit var categoryRepository: CategoryRepository
    private lateinit var itemRepository: ItemRepository
    private lateinit var userRepository: UserRepository
    private lateinit var service: AdminService

    private val admin = "admin-1"

    @BeforeEach
    fun setup() {
        categoryRepository = mock(CategoryRepository::class.java)
        itemRepository = mock(ItemRepository::class.java)
        userRepository = mock(UserRepository::class.java)
        service =
            AdminService(
                categoryRepository,
                itemRepository,
                userRepository,
                mock(AuditService::class.java),
            )
    }

    @Test
    fun `updateCategoryRewards 해피 패스 — 필드 갱신 후 save`() {
        val category = Category(id = 1L, name = "산책", tokenName = "산책토큰")
        `when`(categoryRepository.findById(1L)).thenReturn(Optional.of(category))

        service.updateCategoryRewards(admin, categoryId = 1L, baseCoinReward = 30, baseTokenReward = 15, dailyLimit = 7)

        assertEquals(30, category.baseCoinReward)
        assertEquals(15, category.baseTokenReward)
        assertEquals(7, category.dailyLimit)
        verify(categoryRepository).save(category)
    }

    @Test
    fun `updateCategoryRewards — 음수 보상은 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            service.updateCategoryRewards(admin, categoryId = 1L, baseCoinReward = -1, baseTokenReward = 10, dailyLimit = 5)
        }
    }

    @Test
    fun `dashboard — 카운트 집계`() {
        `when`(userRepository.count()).thenReturn(10L)
        `when`(itemRepository.count()).thenReturn(20L)
        `when`(categoryRepository.count()).thenReturn(4L)

        val d = service.dashboard()

        assertEquals(10L, d.totalUsers)
        assertEquals(20L, d.totalItems)
        assertEquals(4L, d.totalCategories)
    }

    @Test
    fun `setItemActive — 아이템 활성 토글 후 save`() {
        val item =
            Item(id = 1L, name = "테스트 아이템", priceType = PriceType.BASIC, priceAmount = 100, assetUrl = "x.png")
        `when`(itemRepository.findById(1L)).thenReturn(Optional.of(item))

        service.setItemActive(admin, itemId = 1L, active = false)

        assertEquals(false, item.isActive)
        verify(itemRepository).save(item)
    }

    @Test
    fun `setItemActive — 미존재 아이템은 BusinessException`() {
        `when`(itemRepository.findById(99L)).thenReturn(Optional.empty())
        assertThrows<BusinessException> {
            service.setItemActive(admin, itemId = 99L, active = true)
        }
    }

    @Test
    fun `createItem 해피 패스 — slug 고유 + 카테고리 없음 → save 후 반환`() {
        `when`(itemRepository.findBySlug("plant-1")).thenReturn(Optional.empty())
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        val created =
            service.createItem(
                adminUserId = admin,
                name = "작은 선인장",
                slug = "plant-1",
                description = "데스크 위 친구",
                categoryId = null,
                priceType = PriceType.BASIC,
                priceAmount = 30,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "🌵",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )

        assertEquals("작은 선인장", created.name)
        assertEquals("plant-1", created.slug)
        assertEquals(true, created.isActive)
        verify(itemRepository).save(org.mockito.kotlin.any<Item>())
    }

    @Test
    fun `createItem — slug 중복은 BusinessException`() {
        val existing =
            Item(id = 7L, slug = "dup", name = "기존", priceType = PriceType.BASIC, priceAmount = 10, assetUrl = "x")
        `when`(itemRepository.findBySlug("dup")).thenReturn(Optional.of(existing))

        assertThrows<BusinessException> {
            service.createItem(
                adminUserId = admin,
                name = "새 아이템",
                slug = "dup",
                description = null,
                categoryId = null,
                priceType = PriceType.BASIC,
                priceAmount = 10,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "y",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        }
    }

    @Test
    fun `createItem — 미존재 카테고리는 BusinessException`() {
        `when`(categoryRepository.findById(99L)).thenReturn(Optional.empty())

        assertThrows<BusinessException> {
            service.createItem(
                adminUserId = admin,
                name = "새 아이템",
                slug = null,
                description = null,
                categoryId = 99L,
                priceType = PriceType.BASIC,
                priceAmount = 10,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "y",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        }
    }

    @Test
    fun `createItem TOKEN — category 누락은 INVALID_INPUT `() {
        val ex =
            assertThrows<BusinessException> {
                service.createItem(
                    adminUserId = admin,
                    name = "토큰 아이템",
                    slug = null,
                    description = null,
                    categoryId = null,
                    priceType = PriceType.TOKEN,
                    priceAmount = 10,
                    tokenPrice = null,
                    rarity = com.terraworld.domain.item.Rarity.COMMON,
                    assetUrl = "y",
                    layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                    isAnimated = false,
                    width = 512,
                    height = 512,
                )
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `createItem TOKEN — 커스텀 카테고리(원소 토큰 없음)는 INVALID_INPUT `() {
        `when`(categoryRepository.findById(5L))
            .thenReturn(Optional.of(Category(id = 5L, name = "커스텀", tokenName = "커스텀토큰")))
        val ex =
            assertThrows<BusinessException> {
                service.createItem(
                    adminUserId = admin,
                    name = "토큰 아이템",
                    slug = null,
                    description = null,
                    categoryId = 5L,
                    priceType = PriceType.TOKEN,
                    priceAmount = 10,
                    tokenPrice = null,
                    rarity = com.terraworld.domain.item.Rarity.COMMON,
                    assetUrl = "y",
                    layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                    isAnimated = false,
                    width = 512,
                    height = 512,
                )
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `createItem TOKEN — 시스템 카테고리(1~4)는 성공`() {
        `when`(categoryRepository.findById(1L))
            .thenReturn(Optional.of(Category(id = 1L, name = "산책", tokenName = "산책토큰")))
        `when`(itemRepository.findBySlug(org.mockito.kotlin.any())).thenReturn(Optional.empty())
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        val created =
            service.createItem(
                adminUserId = admin,
                name = "산책 돌",
                slug = "walk-stone",
                description = null,
                categoryId = 1L,
                priceType = PriceType.TOKEN,
                priceAmount = 20,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "🪨",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        assertEquals(PriceType.TOKEN, created.priceType)
        assertEquals(1L, created.category?.id)
    }

    @Test
    fun `createItem — assetUrl 사설망 host 는 BusinessException (SEC-003 SSRF)`() {
        assertThrows<BusinessException> {
            service.createItem(
                adminUserId = admin,
                name = "새 아이템",
                slug = null,
                description = null,
                categoryId = null,
                priceType = PriceType.BASIC,
                priceAmount = 10,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "https://169.254.169.254/x.png",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        }
    }

    @Test
    fun `createItem — assetUrl http(비 https) 는 BusinessException`() {
        assertThrows<BusinessException> {
            service.createItem(
                adminUserId = admin,
                name = "새 아이템",
                slug = null,
                description = null,
                categoryId = null,
                priceType = PriceType.BASIC,
                priceAmount = 10,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "http://cdn.example.com/x.png",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        }
    }

    @Test
    fun `createItem — 음수 가격은 IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            service.createItem(
                adminUserId = admin,
                name = "새 아이템",
                slug = null,
                description = null,
                categoryId = null,
                priceType = PriceType.BASIC,
                priceAmount = -1,
                tokenPrice = null,
                rarity = com.terraworld.domain.item.Rarity.COMMON,
                assetUrl = "y",
                layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
                isAnimated = false,
                width = 512,
                height = 512,
            )
        }
    }
}
