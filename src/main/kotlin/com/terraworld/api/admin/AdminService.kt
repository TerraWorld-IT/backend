package com.terraworld.api.admin

import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * N5 / P-ADMIN-001 (구현 계획서 v4, 2026-05-21): 관리자 backend API.
 *
 * 변경 사유: frontend admin 5 페이지에 대응하는 backend 가 전무했음 (읽기는
 * 기존 공개 endpoint 재사용, 쓰기는 placeholder). 본 service 는 게임 밸런스 튜닝
 * (카테고리 보상 / 레벨 곡선 / 교환 비율) + 아이템 활성 토글을 제공.
 *
 * 권한: `AdminController` 가 admin 하위 경로 — `SecurityConfig` 의
 * `.hasRole("ADMIN")` 게이트 (JwtAuthenticationFilter 가 ROLE_ADMIN authority 부여).
 * 모든 쓰기는 AuditService 에 기록.
 */
@Service
class AdminService(
    private val categoryRepository: CategoryRepository,
    private val levelConfigRepository: LevelConfigRepository,
    private val tokenExchangeRateRepository: TokenExchangeRateRepository,
    private val itemRepository: ItemRepository,
    private val userRepository: UserRepository,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 카테고리 보상 조정 (이슬/토큰 지급량, 일일 한도). */
    @Transactional
    fun updateCategoryRewards(
        adminUserId: String,
        categoryId: Long,
        baseCoinReward: Int,
        baseTokenReward: Int,
        dailyLimit: Int,
    ) {
        require(baseCoinReward >= 0 && baseTokenReward >= 0 && dailyLimit >= 1) {
            "보상/한도 값은 음수 불가, dailyLimit ≥ 1"
        }
        val category =
            categoryRepository
                .findById(categoryId)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        category.baseCoinReward = baseCoinReward
        category.baseTokenReward = baseTokenReward
        category.dailyLimit = dailyLimit
        categoryRepository.save(category)
        audit(adminUserId, "ADMIN_UPDATE_CATEGORY_REWARDS", "Category", categoryId.toString())
        log.info("admin.category.rewards user={} category={}", adminUserId, categoryId)
    }

    /** 레벨 설정 조정 (required_exp / 보상). N4 의 EXP 곡선 runtime 조정 경로. */
    @Transactional
    fun updateLevelConfig(
        adminUserId: String,
        level: Int,
        requiredExp: Long,
        rewardType: String?,
        rewardValue: Int?,
        maxItems: Int,
    ) {
        require(requiredExp >= 0 && maxItems >= 1) { "requiredExp ≥ 0, maxItems ≥ 1" }
        // CDX-002: rewardValue 음수 차단
        require(rewardValue == null || rewardValue >= 0) { "rewardValue 는 음수 불가" }
        val config =
            levelConfigRepository
                .findById(level)
                .orElseThrow { BusinessException(ErrorCode.INVALID_INPUT, "레벨 설정 없음: $level") }
        config.requiredExp = requiredExp
        config.rewardType = rewardType
        config.rewardValue = rewardValue
        config.maxItems = maxItems
        levelConfigRepository.save(config)
        audit(adminUserId, "ADMIN_UPDATE_LEVEL_CONFIG", "LevelConfig", level.toString())
        log.info("admin.level.config user={} level={} requiredExp={}", adminUserId, level, requiredExp)
    }

    /** 토큰 교환 비율 조정. */
    @Transactional
    fun updateExchangeRate(
        adminUserId: String,
        rateId: Long,
        rate: Float,
        isActive: Boolean,
    ) {
        // CDX-002: rate 는 0 초과 + 유한값 (Infinity/NaN 차단)
        require(rate > 0f && rate.isFinite()) { "교환 비율은 0 보다 큰 유한값이어야 함" }
        val exchangeRate =
            tokenExchangeRateRepository
                .findById(rateId)
                .orElseThrow { BusinessException(ErrorCode.EXCHANGE_RATE_NOT_FOUND) }
        exchangeRate.rate = rate
        exchangeRate.isActive = isActive
        tokenExchangeRateRepository.save(exchangeRate)
        audit(adminUserId, "ADMIN_UPDATE_EXCHANGE_RATE", "TokenExchangeRate", rateId.toString())
        log.info("admin.exchange.rate user={} rate={} value={}", adminUserId, rateId, rate)
    }

    /** 아이템 활성/비활성 토글 (상점 노출 제어 — soft delete 대용). */
    @Transactional
    fun setItemActive(
        adminUserId: String,
        itemId: Long,
        active: Boolean,
    ) {
        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }
        item.isActive = active
        itemRepository.save(item)
        audit(adminUserId, "ADMIN_SET_ITEM_ACTIVE", "Item", itemId.toString())
        log.info("admin.item.active user={} item={} active={}", adminUserId, itemId, active)
    }

    /** admin 대시보드 요약 카운트. */
    @Transactional(readOnly = true)
    fun dashboard(): AdminDashboard =
        AdminDashboard(
            totalUsers = userRepository.count(),
            totalItems = itemRepository.count(),
            totalCategories = categoryRepository.count(),
            totalLevels = levelConfigRepository.count(),
        )

    private fun audit(
        adminUserId: String,
        action: String,
        resourceType: String,
        resourceId: String,
    ) {
        auditService.publish(
            userId = adminUserId,
            action = action,
            resourceType = resourceType,
            resourceId = resourceId,
            payload = emptyMap(),
        )
    }
}

data class AdminDashboard(
    val totalUsers: Long,
    val totalItems: Long,
    val totalCategories: Long,
    val totalLevels: Long,
)
