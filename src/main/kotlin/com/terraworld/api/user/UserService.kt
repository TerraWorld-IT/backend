package com.terraworld.api.user

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.EntitlementsResponse
import io.terraworld.api.model.PlacedItemResponse
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
    private val userItemRepository: UserItemRepository,
    private val terrariumRepository: TerrariumRepository,
    private val walletBuilder: WalletBuilder,
    private val entitlementService: com.terraworld.api.entitlement.EntitlementService,
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
            currency = walletBuilder.build(userId, user),
            ownedItems = ownedItems,
            placedItems = placedItems,
            // N3 (구현 계획서 v4): entitlement SoT = user_entitlement 테이블.
            // user.entitlement* boolean 은 deprecated — EntitlementService 단일 조회.
            entitlements =
                EntitlementsResponse(
                    freePlacement =
                        entitlementService.hasEntitlement(
                            userId,
                            com.terraworld.domain.entitlement.UserEntitlementId.FREE_PLACEMENT,
                        ),
                    premiumThemes =
                        entitlementService.hasEntitlement(
                            userId,
                            com.terraworld.domain.entitlement.UserEntitlementId.PREMIUM_THEMES,
                        ),
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
