package com.terraworld.api.admin

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
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
 * @Hidden — OpenAPI spec 미포함 (internal admin). frontend 는 raw authed fetch 로 호출.
 * 본 cycle 에서 backend API 우선 구현 — admin 페이지의 쓰기 폼 wiring 은 후속 (현재 placeholder).
 */
@RestController
@RequestMapping("/api/v1/admin")
@Hidden
class AdminController(
    private val adminService: AdminService,
) {
    @GetMapping("/dashboard")
    fun dashboard(): ResponseEntity<AdminDashboard> = ResponseEntity.ok(adminService.dashboard())

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

    @PutMapping("/levels/{level}")
    fun updateLevelConfig(
        @PathVariable level: Int,
        @RequestBody body: LevelConfigRequest,
    ): ResponseEntity<Void> {
        adminService.updateLevelConfig(
            adminUserId = SecurityUtil.getCurrentUserId(),
            level = level,
            requiredExp = body.requiredExp,
            rewardType = body.rewardType,
            rewardValue = body.rewardValue,
            maxItems = body.maxItems,
        )
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/exchange-rates/{rateId}")
    fun updateExchangeRate(
        @PathVariable rateId: Long,
        @RequestBody body: ExchangeRateRequest,
    ): ResponseEntity<Void> {
        adminService.updateExchangeRate(
            adminUserId = SecurityUtil.getCurrentUserId(),
            rateId = rateId,
            rate = body.rate,
            isActive = body.isActive,
        )
        return ResponseEntity.noContent().build()
    }

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

data class LevelConfigRequest(
    val requiredExp: Long,
    val rewardType: String? = null,
    val rewardValue: Int? = null,
    val maxItems: Int,
)

data class ExchangeRateRequest(
    val rate: Float,
    val isActive: Boolean = true,
)

data class ItemActiveRequest(
    val active: Boolean,
)
