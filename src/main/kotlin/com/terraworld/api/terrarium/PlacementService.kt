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
import kotlin.math.roundToInt

/**
 * code-review ARCH-003 (2026-05-21): `PlacementController` 가 repository 직접 의존 +
 * controller 레벨 `@Transactional` 이었던 레이어링 위반 해소. 자유배치 조회/저장 비즈니스
 * 로직 (entitlement 검증 + 소유권 검증 + entity mutation) 을 본 service 로 이동.
 * controller → service → repository 표준 레이어링 + 트랜잭션 경계는 service 메서드.
 *
 * 좌표계 (2026-06-02): API/도메인 경계는 0~1 비율(posX/posY). 저장은 기존 free_x/y_pixel(Int)
 * 컬럼을 permille(0~10000) 로 재사용 — 해상도 독립 + 0.01% 정밀도 + migration 불요.
 */
@Service
class PlacementService(
    private val placementRepository: TerrariumPlacementRepository,
    private val entitlementService: EntitlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 현재 사용자의 모든 배치 아이템 + 자유 위치 (조회는 entitlement 불요 — preview 허용). */
    @Transactional(readOnly = true)
    fun listForUser(userId: String): List<FreePlacementItem> = placementRepository.findAllByTerrariumUserIdOrderById(userId).map { it.toFreeItem() }

    /**
     * 자유배치 위치 저장 — J-FREE-001.
     * 1) free_placement entitlement 검증 (없으면 403)
     * 2) 본인 소유 placement 검증 (cross-user 면 403)
     * 3) 위치 저장 (API 는 0~1 비율, 저장은 permille 0~10000)
     */
    @Transactional
    fun updateFreePosition(
        userId: String,
        placementId: Long,
        posX: Double,
        posY: Double,
        scale: Float? = null,
        flipped: Boolean? = null,
        zIndex: Int? = null,
    ): FreePlacementItem {
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

        placement.freeXPixel = ratioToStore(posX)
        placement.freeYPixel = ratioToStore(posY)
        placement.isFreePlacement = true
        // 낙서장 자유배치 편집 영속(req3 #2): 크기/반전/깊이는 미지정(null) 시 기존값 유지.
        if (scale != null) placement.scale = scale.coerceIn(0.3f, 4.0f)
        if (flipped != null) placement.flipped = flipped
        if (zIndex != null) placement.zIndex = zIndex
        val saved = placementRepository.save(placement)
        log.info("placement.free.saved user={} placementId={} x={} y={}", userId, placementId, posX, posY)
        return saved.toFreeItem()
    }

    private fun TerrariumPlacement.toFreeItem(): FreePlacementItem =
        FreePlacementItem(
            placementId = id,
            itemId = item.id,
            itemName = item.name,
            itemImage = item.assetUrl,
            itemLayout = item.layout.name,
            posX = storeToRatio(freeXPixel),
            posY = storeToRatio(freeYPixel),
            isFreePlacement = isFreePlacement,
            scale = scale,
            flipped = flipped,
            zIndex = zIndex,
        )

    companion object {
        // 자유배치 좌표는 해상도 독립 0~1 비율로 통신. 저장은 기존 free_x/y_pixel(Int) 컬럼을
        // permille(0~10000) 로 재사용 — 0.01% 정밀도, 별도 migration 불요.
        private const val STORE_SCALE = 10_000

        private fun ratioToStore(ratio: Double): Int = (ratio.coerceIn(0.0, 1.0) * STORE_SCALE).roundToInt()

        // internal: TerrariumService 의 freePlacements 응답 조립도 같은 permille→비율 변환을 재사용 (상수 중복 방지).
        internal fun storeToRatio(stored: Int?): Double = (stored ?: 0) / STORE_SCALE.toDouble()
    }
}
