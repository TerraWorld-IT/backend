package com.terraworld.api.admin

import com.terraworld.api.item.ItemMapper
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.Rarity
import com.terraworld.security.SecurityUtil
import io.terraworld.api.model.AdminItemCreateRequest
import io.terraworld.api.model.ItemListResponse
import io.terraworld.api.model.ItemResponse
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * N5 / P-ADMIN-001 (구현 계획서 v4, 2026-05-21): 관리자 backend API.
 *
 * 경로 admin 하위는 `SecurityConfig` 에서 `.hasRole("ADMIN")` 게이트.
 * frontend admin 페이지들의 백엔드 대응 (게임 밸런스 튜닝 + 아이템 활성 토글 + 대시보드).
 *
 * 본 cycle 에서 backend API 우선 구현 — admin 페이지의 쓰기 폼 wiring 은 후속 (현재 placeholder).
 */
@RestController
@RequestMapping("/api/v1/admin")
class AdminController(
    private val adminService: AdminService,
) {
    @GetMapping("/dashboard")
    fun dashboard(): ResponseEntity<AdminDashboard> = ResponseEntity.ok(adminService.dashboard())

    // M2 (code-review R1): 공개 목록(GET /items)은 활성만 반환 → 비활성 아이템이 admin 목록에서 사라져 재활성화 불가.
    // 관리자 전용 전체 목록(비활성 포함) 제공으로 재활성화 경로 확보.
    @GetMapping("/items")
    fun listAllItems(): ResponseEntity<ItemListResponse> = ResponseEntity.ok(ItemListResponse(items = adminService.listAllItems().map(ItemMapper::toApi)))

    @PostMapping("/items")
    fun createItem(
        @Valid @RequestBody body: AdminItemCreateRequest,
    ): ResponseEntity<ItemResponse> {
        val item =
            adminService.createItem(
                adminUserId = SecurityUtil.getCurrentUserId(),
                name = body.name,
                slug = body.slug,
                description = body.description,
                categoryId = body.categoryId,
                priceType = PriceType.valueOf(body.priceType.value),
                priceAmount = body.priceAmount,
                tokenPrice = body.tokenPrice,
                rarity = Rarity.valueOf((body.rarity ?: AdminItemCreateRequest.Rarity.COMMON).value),
                assetUrl = body.assetUrl,
                layout = ItemLayout.valueOf((body.layout ?: AdminItemCreateRequest.Layout.FOREGROUND).value),
                isAnimated = body.isAnimated ?: false,
                width = body.width ?: 512,
                height = body.height ?: 512,
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(ItemMapper.toApi(item))
    }

    @PutMapping("/categories/{categoryId}/rewards")
    fun updateCategoryRewards(
        @PathVariable categoryId: Long,
        @RequestBody body: CategoryRewardsRequest,
    ): ResponseEntity<Void> {
        adminService.updateCategoryRewards(
            adminUserId = SecurityUtil.getCurrentUserId(),
            categoryId = categoryId,
            baseCoinReward = body.baseCoinReward,
            baseTokenReward = body.baseTokenReward,
            dailyLimit = body.dailyLimit,
        )
        return ResponseEntity.noContent().build()
    }

    // H2 (code-review R1/R2): 구 admin 환전 endpoint(updateExchangeRate) 제거 — 편집 대상(token_exchange_rates)이
    //   실 환전(exchange_rates, 7화폐)과 무관해 SDK 호출 시 204 이나 실 환율 미변경(오도). FE 화면·spec·SDK 모두 제거됨.
    @PutMapping("/items/{itemId}/active")
    fun setItemActive(
        @PathVariable itemId: Long,
        @RequestBody body: ItemActiveRequest,
    ): ResponseEntity<Void> {
        adminService.setItemActive(
            adminUserId = SecurityUtil.getCurrentUserId(),
            itemId = itemId,
            active = body.active,
        )
        return ResponseEntity.noContent().build()
    }
}

data class CategoryRewardsRequest(
    val baseCoinReward: Int,
    val baseTokenReward: Int,
    val dailyLimit: Int,
)

data class ItemActiveRequest(
    val active: Boolean,
)
