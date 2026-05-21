package com.terraworld.api.terrarium

import com.terraworld.api.entitlement.EntitlementService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.terrarium.TerrariumPlacement
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * code-review ARCH-003 (2026-05-21): `PlacementController` 가 repository 직접 의존 +
 * controller 레벨 `@Transactional` 이었던 레이어링 위반 해소. 자유배치 저장 비즈니스
 * 로직 (entitlement 검증 + 소유권 검증 + entity mutation) 을 본 service 로 이동.
 * controller → service → repository 표준 레이어링 + 트랜잭션 경계는 service 메서드.
 */
@Service
class PlacementService(
    private val placementRepository: TerrariumPlacementRepository,
    private val entitlementService: EntitlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 자유배치 위치 저장 — J-FREE-001.
     * 1) free_placement entitlement 검증 (없으면 403)
     * 2) 본인 소유 placement 검증 (cross-user 면 403)
     * 3) free_x/y_pixel + is_free_placement 저장
     */
    @Transactional
    fun updateFreePosition(
        userId: String,
        placementId: Long,
        freeXPixel: Int,
        freeYPixel: Int,
    ): TerrariumPlacement {
        if (!entitlementService.hasEntitlement(userId, UserEntitlementId.FREE_PLACEMENT)) {
            log.warn("placement.free.forbidden user={} placementId={}", userId, placementId)
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        val placement =
            placementRepository
                .findById(placementId)
                .orElseThrow { BusinessException(ErrorCode.PLACEMENT_NOT_FOUND) }
        if (placement.terrarium.user.id != userId) {
            log.warn("placement.free.cross-user user={} placement.user={}", userId, placement.terrarium.user.id)
            throw BusinessException(ErrorCode.FORBIDDEN)
        }

        placement.freeXPixel = freeXPixel
        placement.freeYPixel = freeYPixel
        placement.isFreePlacement = true
        val saved = placementRepository.save(placement)
        log.info("placement.free.saved user={} placementId={} x={} y={}", userId, placementId, freeXPixel, freeYPixel)
        return saved
    }
}
