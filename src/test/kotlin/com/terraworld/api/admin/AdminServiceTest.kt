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
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.test.util.ReflectionTestUtils
import java.security.SecureRandom
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
    fun `createItem — slug 누락과 공백은 이름을 정규화하여 생성`() {
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        for (slug in listOf(null, "", "  ")) {
            val created = createSlugItem("  Café -- Garden １２!  ", slug)
            assertTrue(Regex("cafe-garden-12-[0-9a-f]{6}").matches(assertNotNull(created.slug)))
        }
    }

    @Test
    fun `createItem — 한글과 기호 이름은 item 접두어로 생성`() {
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        for (name in listOf("작은 선인장", "🌵 !!!")) {
            val created = createSlugItem(name)
            assertTrue(Regex("item-[0-9a-f]{6}").matches(assertNotNull(created.slug)))
        }
    }

    @Test
    fun `createItem — 긴 이름은 접미어를 보존하고 전체 50자 이하로 생성`() {
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        val slug = assertNotNull(createSlugItem("A".repeat(80)).slug)
        assertEquals(50, slug.length)
        assertTrue(Regex("a{43}-[0-9a-f]{6}").matches(slug))
        val boundarySlug = assertNotNull(createSlugItem("A".repeat(42) + "-long-name").slug)
        assertTrue(Regex("a{42}-[0-9a-f]{6}").matches(boundarySlug))
    }

    @Test
    fun `createItem — 생성 slug 는 정령 전용 접미어와 충돌하지 않는다`() {
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        val slug = assertNotNull(createSlugItem("cat-spirit").slug)
        assertTrue(Regex("cat-spirit-[0-9a-f]{6}").matches(slug))
        assertFalse(slug.endsWith("-spirit"))
    }

    @Test
    fun `createItem — 명시 slug 는 기존 공백 제거 외에 변경하지 않는다`() {
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        assertEquals("My_Custom-Slug", createSlugItem("다른 이름", "  My_Custom-Slug  ").slug)
        verify(itemRepository).findBySlug("My_Custom-Slug")
    }

    @Test
    fun `createItem — 생성 slug 충돌 시 새 접미어로 재시도`() {
        val random = mock(SecureRandom::class.java)
        var digit = 0
        `when`(random.nextInt(16)).thenAnswer { digit++ / 6 }
        ReflectionTestUtils.setField(service, "random", random)
        val existing = Item(name = "기존", priceType = PriceType.BASIC, priceAmount = 0, assetUrl = "x")
        `when`(itemRepository.findBySlug("garden-000000")).thenReturn(Optional.of(existing))
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        assertEquals("garden-111111", createSlugItem("Garden").slug)
        verify(itemRepository).findBySlug("garden-000000")
        verify(itemRepository).findBySlug("garden-111111")
        verify(itemRepository, times(2)).findBySlug(org.mockito.kotlin.any())
    }

    @Test
    fun `createItem — 다섯 번째 생성 후보까지 성공 가능`() {
        val existing = Item(name = "기존", priceType = PriceType.BASIC, priceAmount = 0, assetUrl = "x")
        var attempts = 0
        `when`(itemRepository.findBySlug(org.mockito.kotlin.any())).thenAnswer {
            if (++attempts < 5) Optional.of(existing) else Optional.empty<Item>()
        }
        `when`(itemRepository.save(org.mockito.kotlin.any<Item>())).thenAnswer { it.arguments[0] as Item }

        assertNotNull(createSlugItem("Garden").slug)
        verify(itemRepository, times(5)).findBySlug(org.mockito.kotlin.any())
    }

    @Test
    fun `createItem — 다섯 번 충돌하면 기존 중복 오류로 종료하고 저장하지 않는다`() {
        val existing = Item(name = "기존", priceType = PriceType.BASIC, priceAmount = 0, assetUrl = "x")
        `when`(itemRepository.findBySlug(org.mockito.kotlin.any())).thenReturn(Optional.of(existing))

        val ex = assertThrows<BusinessException> { createSlugItem("Garden") }
        assertEquals(ErrorCode.ITEM_SLUG_DUPLICATE, ex.errorCode)
        verify(itemRepository, times(5)).findBySlug(org.mockito.kotlin.any())
        verify(itemRepository, never()).save(org.mockito.kotlin.any<Item>())
    }

    @Test
    fun `생성기는 빈 이름도 처리하지만 createItem 의 빈 이름 거부 계약은 유지`() {
        val slug = ReflectionTestUtils.invokeMethod<String>(service, "generateUniqueSlug", "")
        assertTrue(Regex("item-[0-9a-f]{6}").matches(assertNotNull(slug)))
        assertThrows<IllegalArgumentException> { createSlugItem("") }
        assertThrows<IllegalArgumentException> { createSlugItem("  ") }
        verify(itemRepository, never()).save(org.mockito.kotlin.any<Item>())
    }

    private fun createSlugItem(
        name: String,
        slug: String? = null,
    ): Item =
        service.createItem(
            adminUserId = admin,
            name = name,
            slug = slug,
            description = null,
            categoryId = null,
            priceType = PriceType.BASIC,
            priceAmount = 10,
            tokenPrice = null,
            rarity = com.terraworld.domain.item.Rarity.COMMON,
            assetUrl = "🌵",
            layout = com.terraworld.domain.item.ItemLayout.FOREGROUND,
            isAnimated = false,
            width = 512,
            height = 512,
        )

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
