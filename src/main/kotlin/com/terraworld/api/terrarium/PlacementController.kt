package com.terraworld.api.terrarium

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Hidden
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * UltraPlan v3 J-FREE-001 (2026-05-18) + N6 (구현 계획서 v4):
 *
 * 자유배치 (slot 외 자유 위치) 저장 endpoint. 흐름:
 *  1) IAP 결제 → EntitlementService.grant(userId, "free_placement", ...)
 *  2) frontend `pages/terrarium/free.vue` 에서 위치 변경 → 본 endpoint 호출
 *  3) entitlement 체크 → 없으면 403, 있으면 free_x/y_pixel + is_free_placement 저장
 *  4) IAP 환불 → revoke → 다음 호출 시 403
 *
 * code-review ARCH-003: 비즈니스 로직 + 트랜잭션 경계는 `PlacementService` 로 이동.
 * 본 controller 는 인증 추출 + service 위임 + 응답 조립만.
 *
 * @Hidden — OpenAPI spec 미포함 (internal). frontend 가 raw authed fetch 로 호출.
 */
@RestController
@RequestMapping("/api/v1/terrarium/free-placement")
@Hidden
class PlacementController(
    private val placementService: PlacementService,
) {
    @PutMapping("/{placementId}")
    fun updateFreePosition(
        @PathVariable placementId: Long,
        @RequestBody body: FreePositionRequest,
    ): ResponseEntity<FreePositionResponse> {
        val placement =
            placementService.updateFreePosition(
                userId = SecurityUtil.getCurrentUserId(),
                placementId = placementId,
                freeXPixel = body.freeXPixel,
                freeYPixel = body.freeYPixel,
            )
        return ResponseEntity.ok(
            FreePositionResponse(
                placementId = placement.id,
                freeXPixel = placement.freeXPixel ?: body.freeXPixel,
                freeYPixel = placement.freeYPixel ?: body.freeYPixel,
                isFreePlacement = placement.isFreePlacement,
                message = "자유 위치 저장 완료",
            ),
        )
    }
}

data class FreePositionRequest(
    val freeXPixel: Int,
    val freeYPixel: Int,
)

data class FreePositionResponse(
    val placementId: Long,
    val freeXPixel: Int,
    val freeYPixel: Int,
    val isFreePlacement: Boolean,
    val message: String,
)
