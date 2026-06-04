package com.terraworld.api.admin

import com.terraworld.common.audit.AuditService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.exchange.TokenExchangeRateRepository
import com.terraworld.domain.item.Item
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.PriceType
import com.terraworld.domain.item.Rarity
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI

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

    /**
     * 아이템 생성 (admin 상점 카탈로그 등록). (WP-W9, fullstack-ultraplan 2026-06-04)
     *
     * slug 제공 시 고유성 검증(중복 = 409). category 지정 시 존재 검증(부재 = 404).
     * 생성 직후 isActive=true. 입력 검증은 generated DTO(@Min/@Size) + 본 가드 이중.
     */
    @Transactional
    fun createItem(
        adminUserId: String,
        name: String,
        slug: String?,
        description: String?,
        categoryId: Long?,
        priceType: PriceType,
        priceAmount: Int,
        tokenPrice: Int?,
        rarity: Rarity,
        assetUrl: String,
        layout: ItemLayout,
        isAnimated: Boolean,
        width: Int,
        height: Int,
    ): Item {
        require(name.isNotBlank()) { "name 은 공백 불가" }
        require(assetUrl.isNotBlank()) { "assetUrl 은 공백 불가" }
        require(priceAmount >= 0) { "priceAmount 는 음수 불가" }
        require(tokenPrice == null || tokenPrice >= 0) { "tokenPrice 는 음수 불가" }
        require(width >= 1 && height >= 1) { "width/height 는 1 이상" }
        // Codex SEC-003: assetUrl 이 URL 형태면 https + 사설망/localhost reject (stored tracking pixel + 내부망 SSRF 차단).
        validateAssetUrl(assetUrl.trim())

        val normalizedSlug = slug?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedSlug != null && itemRepository.findBySlug(normalizedSlug).isPresent) {
            throw BusinessException(ErrorCode.ITEM_SLUG_DUPLICATE)
        }
        val category =
            categoryId?.let {
                categoryRepository.findById(it).orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
            }
        val saved =
            itemRepository.save(
                Item(
                    slug = normalizedSlug,
                    name = name.trim(),
                    description = description?.trim()?.takeIf { it.isNotBlank() },
                    category = category,
                    priceType = priceType,
                    priceAmount = priceAmount,
                    tokenPrice = tokenPrice,
                    rarity = rarity,
                    assetUrl = assetUrl.trim(),
                    layout = layout,
                    isAnimated = isAnimated,
                    width = width,
                    height = height,
                    isActive = true,
                ),
            )
        audit(adminUserId, "ADMIN_CREATE_ITEM", "Item", saved.id.toString())
        log.info("admin.item.create user={} item={} name={}", adminUserId, saved.id, saved.name)
        return saved
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

    /**
     * Codex SEC-003: assetUrl SSRF/tracking 가드.
     * 스킴 없는 값(이모지·상대경로)은 허용. URL 형태면 https 강제 + 사설망/localhost/link-local/metadata host reject.
     */
    private fun validateAssetUrl(assetUrl: String) {
        if (!assetUrl.contains("://")) return // 이모지 또는 상대경로
        val uri =
            runCatching { URI(assetUrl) }.getOrNull()
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "assetUrl 형식이 올바르지 않습니다")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "assetUrl 은 https URL 또는 이모지만 허용됩니다")
        }
        val host =
            uri.host?.lowercase()
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "assetUrl host 가 없습니다")
        if (isPrivateOrLocalHost(host)) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "사설망/로컬 host 는 허용되지 않습니다")
        }
    }

    private fun isPrivateOrLocalHost(host: String): Boolean {
        if (host == "localhost" || host.endsWith(".localhost") || host == "127.0.0.1" || host == "::1") return true
        if (host == "169.254.169.254") return true // cloud metadata endpoint
        if (host.startsWith("10.") || host.startsWith("192.168.") || host.startsWith("169.254.")) return true
        // 172.16.0.0 – 172.31.255.255
        Regex("""^172\.(\d{1,3})\.""").find(host)?.let { m ->
            val second = m.groupValues[1].toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        return false
    }

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
