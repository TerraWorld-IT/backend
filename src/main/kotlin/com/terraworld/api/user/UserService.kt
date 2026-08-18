package com.terraworld.api.user

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
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
    private val terrariumPlacementRepository: TerrariumPlacementRepository,
    private val walletBuilder: WalletBuilder,
    private val entitlementService: com.terraworld.api.entitlement.EntitlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        /** 닉네임 길이 계약 (spec UpdateMeRequest 1..20 — DB 컬럼 50 이내). */
        const val MAX_NICKNAME_LENGTH = 20
    }

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

        // BE-02 (2026-07-15 성능 감사): terrarium.placedItems lazy 컬렉션 + per-row item 로드 N+1 을
        // @EntityGraph(item) 조회로 대체 (TerrariumPlacementRepository 기존 자유배치 로드 패턴 재사용).
        // terrarium 부재 시 빈 목록 — 기존 orElse(emptyList()) semantics 동일.
        val placedItems =
            terrariumPlacementRepository
                .findAllByTerrariumUserIdOrderById(userId)
                .map { p ->
                    PlacedItemResponse(
                        itemId = p.item.id,
                        itemSlug = p.item.slug,
                        slotId = p.slotId,
                    )
                }

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
     * 프로필(닉네임) 갱신 (PUT /users/me, apjek social loop).
     * trim 후 1..20자 검증 — bean validation(@Size)은 raw 값 기준이라 공백-only 입력이
     * 통과할 수 있어 서비스에서 재검증한다. 갱신 후 spec 계약대로 getMe 와 동일한
     * 전체 스냅샷을 반환해 프론트가 상태를 그대로 교체할 수 있게 한다.
     */
    @Transactional
    fun updateMe(
        userId: String,
        email: String,
        nickname: String,
    ): UserMeResponse {
        val trimmed = nickname.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_NICKNAME_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "닉네임은 1~${MAX_NICKNAME_LENGTH}자여야 합니다")
        }
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        user.nickname = trimmed
        user.updatedAt = java.time.LocalDateTime.now()
        userRepository.save(user)
        return getMe(userId, email)
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
