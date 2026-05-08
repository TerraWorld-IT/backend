package com.terraworld.api.user

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.EntitlementsResponse
import io.terraworld.api.model.PlacedItemResponse
import io.terraworld.api.model.ProgressResponse
import io.terraworld.api.model.UserMeResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * ARCH-008-phase-13: 반환 타입을 generated [UserMeResponse] 직접. local UserMeResponse / ProgressResponse /
 * PlacedItemResponse / EntitlementsResponse 모두 generated 로 마이그레이션됨.
 *
 * `role` 은 generated `UserMeResponse.Role` enum 으로 변환 — domain `UserRole` (USER/ADMIN) 의 .name
 * 을 forValue 로. ADR-019 warn log 적용.
 */
@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val userItemRepository: UserItemRepository,
    private val terrariumRepository: TerrariumRepository,
    private val levelConfigRepository: LevelConfigRepository,
    private val currencyBuilder: CurrencyBuilder,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun getMe(
        userId: String,
        email: String,
    ): UserMeResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val nextLevelConfig = levelConfigRepository.findByLevel(user.level + 1)
        val expToNext = nextLevelConfig.map { it.requiredExp - user.totalExp }.orElse(0L)

        val ownedItems =
            userItemRepository
                .findAllByUserId(userId)
                .mapNotNull { it.item.slug }

        val terrarium = terrariumRepository.findByUserId(userId)
        val placedItems =
            terrarium
                .map { t ->
                    t.placedItems.map { p ->
                        PlacedItemResponse(
                            itemId = p.item.id,
                            itemSlug = p.item.slug,
                            slotId = p.slotId,
                        )
                    }
                }.orElse(emptyList())

        return UserMeResponse(
            userId = user.id,
            email = email,
            nickname = user.nickname,
            role = mapRoleOrFallback(user.role.name),
            currency = currencyBuilder.build(userId, user),
            progress =
                ProgressResponse(
                    level = user.level,
                    experience = user.totalExp,
                    experienceToNext = expToNext,
                ),
            ownedItems = ownedItems,
            placedItems = placedItems,
            entitlements =
                EntitlementsResponse(
                    freePlacement = user.entitlementFreePlacement,
                    premiumThemes = user.entitlementPremiumThemes,
                ),
        )
    }

    /**
     * domain UserRole.name → generated [UserMeResponse.Role] enum 변환.
     * spec 외 값이면 warn log + USER fallback (ADR-019 §1 — 기본값 fallback 정책).
     */
    private fun mapRoleOrFallback(role: String): UserMeResponse.Role =
        runCatching { UserMeResponse.Role.forValue(role) }
            .onFailure {
                log.warn(
                    "spec drift: user role='{}' is not in OpenAPI spec — falling back to USER (expected one of: {})",
                    role,
                    UserMeResponse.Role.entries.joinToString { it.value },
                )
            }.getOrDefault(UserMeResponse.Role.USER)
}
