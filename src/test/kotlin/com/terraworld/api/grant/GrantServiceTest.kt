package com.terraworld.api.grant

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.character.CharacterDefRepository
import com.terraworld.domain.character.UserCharacterRepository
import com.terraworld.domain.grant.UserGrantRepository
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.UserItemRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * GrantService 아이템 executor (아프젝 v2 §R3) — 아이템 소유 부여 멱등.
 * - 신규 키: user_grants insert + user_items insertIfAbsent(slug → item id)
 * - 같은 키 재호출: grant 원장 충돌로 false, 소유 upsert 미호출
 * - 이미 보유(다른 키): 원장은 남고 소유는 no-op(수량 증가 없음)
 * - 미존재 slug: INVALID_INPUT (시드 누락 fail-fast)
 * - BACKGROUND: BACKGROUND 레이아웃 item slug 를 user_items 소유로 반영
 * - SpiritItems 매핑 cat ↔ cat-spirit
 */
class GrantServiceTest {
    private val userGrantRepository = mock(UserGrantRepository::class.java)
    private val currencyService = mock(CurrencyService::class.java)
    private val characterDefRepository = mock(CharacterDefRepository::class.java)
    private val userCharacterRepository = mock(UserCharacterRepository::class.java)
    private val itemRepository = mock(ItemRepository::class.java)
    private val userItemRepository = mock(UserItemRepository::class.java)
    private val service = GrantService(userGrantRepository, currencyService, characterDefRepository, userCharacterRepository, itemRepository, userItemRepository)

    private val catSpirit = Item(id = 77L, slug = "cat-spirit", name = "고양이 정령", priceType = PriceType.SPECIAL, priceAmount = 0, assetUrl = "x", purchasable = false)
    private val meadowBackground = Item(id = 88L, slug = "bg-meadow", name = "초원 배경", priceType = PriceType.BASIC, priceAmount = 120, assetUrl = "bg.png", layout = ItemLayout.BACKGROUND)

    @Test
    fun `ITEM — 신규 키면 slug 를 item id 로 해석해 user_items 멱등 소유 부여`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(itemRepository.findBySlug("cat-spirit")).thenReturn(Optional.of(catSpirit))
        whenever(userItemRepository.insertIfAbsent("u", 77L)).thenReturn(1)

        assertTrue(service.grant("u", GrantType.ITEM, "cat-spirit", 1, "growth:c1:spirit", "GROWTH_COMPLETE"))
        verify(userItemRepository).insertIfAbsent("u", 77L)
        verify(currencyService, never()).credit(any(), any(), any(), any(), any(), any())
    }

    @Test
    fun `ITEM — 같은 키 재호출은 false + 소유 upsert 미호출 (멱등)`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(0)
        assertFalse(service.grant("u", GrantType.ITEM, "cat-spirit", 1, "growth:c1:spirit", "GROWTH_COMPLETE"))
        verify(userItemRepository, never()).insertIfAbsent(any(), any())
    }

    @Test
    fun `ITEM — 이미 보유(다른 키) 면 소유 no-op 이지만 grant 는 true (원장 기록)`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(itemRepository.findBySlug("cat-spirit")).thenReturn(Optional.of(catSpirit))
        whenever(userItemRepository.insertIfAbsent("u", 77L)).thenReturn(0)
        assertTrue(service.grant("u", GrantType.ITEM, "cat-spirit", 1, "tier-GRAND_TANK:item", "TIER_UNLOCK"))
    }

    @Test
    fun `ITEM — 미존재 slug 는 INVALID_INPUT`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(itemRepository.findBySlug("ghost")).thenReturn(Optional.empty())
        val ex = assertThrows<BusinessException> { service.grant("u", GrantType.ITEM, "ghost", 1, "k", "S") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `BACKGROUND — 신규 키면 배경 slug 를 user_items 멱등 소유로 반영`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(itemRepository.findBySlug("bg-meadow")).thenReturn(Optional.of(meadowBackground))
        whenever(userItemRepository.insertIfAbsent("u", 88L)).thenReturn(1)

        assertTrue(service.grant("u", GrantType.BACKGROUND, "bg-meadow", 1, "event:bg-meadow", "EVENT"))
        verify(userItemRepository).insertIfAbsent("u", 88L)
    }

    @Test
    fun `BACKGROUND — 배경 레이아웃이 아닌 slug 는 INVALID_INPUT`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(itemRepository.findBySlug("cat-spirit")).thenReturn(Optional.of(catSpirit))

        val ex = assertThrows<BusinessException> { service.grant("u", GrantType.BACKGROUND, "cat-spirit", 1, "event:wrong-bg", "EVENT") }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
        verify(userItemRepository, never()).insertIfAbsent(any(), any())
    }

    @Test
    fun `SPIRIT — 기존 경로 유지 (character_defs 검증 + user_characters 멱등)`() {
        whenever(userGrantRepository.insertIfAbsent(any(), any(), any(), any(), any(), any())).thenReturn(1)
        whenever(characterDefRepository.existsById("pigeon")).thenReturn(true)
        assertTrue(service.grant("u", GrantType.SPIRIT, "pigeon", 1, "tier-GRAND_TANK", "TIER_UNLOCK"))
        verify(userCharacterRepository).insertIfAbsent(eq("u"), eq("pigeon"), eq("TIER_UNLOCK"))
    }

    @Test
    fun `SpiritItems — 캐릭터 코드와 정령 아이템 slug 상호 매핑`() {
        assertEquals("cat-spirit", SpiritItems.slugForCharacter("cat"))
        assertEquals("pigeon", SpiritItems.characterForSlug("pigeon-spirit"))
        assertEquals(null, SpiritItems.characterForSlug("bg-meadow"))
    }
}
