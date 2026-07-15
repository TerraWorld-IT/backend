package com.terraworld.api.item

import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.Rarity
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import com.terraworld.test.AbstractMvcTest
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.Optional

@WebMvcTest(ItemController::class)
@AutoConfigureMockMvc(addFilters = false)
class ItemControllerMvcTest : AbstractMvcTest() {
    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var itemRepository: ItemRepository

    // BE-08: 카탈로그 목록은 ItemCatalogService(캐시 컴포넌트) 경유
    @MockBean private lateinit var itemCatalogService: ItemCatalogService

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `GET _api_v1_items 200`() {
        whenever(itemCatalogService.listActiveItems(null, null, null)).thenReturn(listOf(ItemMapper.toApi(stubItem())))

        mockMvc
            .perform(get("/api/v1/items"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].id").value(1))
            .andExpect(jsonPath("$.items[0].priceType").value("BASIC"))
    }

    @Test
    fun `GET _api_v1_items_id 200`() {
        whenever(itemRepository.findById(eq(1L))).thenReturn(Optional.of(stubItem()))

        mockMvc
            .perform(get("/api/v1/items/1"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(1))
            .andExpect(jsonPath("$.name").value("테스트 아이템"))
    }

    private fun stubItem() =
        Item(
            id = 1L,
            slug = "test-item",
            name = "테스트 아이템",
            priceType = PriceType.BASIC,
            priceAmount = 100,
            rarity = Rarity.COMMON,
            assetUrl = "https://cdn.test/asset.png",
            layout = ItemLayout.FOREGROUND,
        )
}
