package com.terraworld.api.terrarium

import com.fasterxml.jackson.annotation.JsonProperty
import com.terraworld.security.SecurityUtil
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * UltraPlan v3 J-FREE-001 (2026-05-18) + N6 (구현 계획서 v4):
 *
 * 자유배치 (slot 외 자유 위치) 조회/저장 endpoint. 흐름:
 *  1) IAP 결제 → EntitlementService.grant(userId, "free_placement", ...)
 *  2) frontend `pages/terrarium/free.vue` 가 GET 으로 현재 배치 아이템 + 자유 위치 로드
 *  3) 드래그 후 PUT 으로 위치 저장 → entitlement 체크 (없으면 403)
 *  4) IAP 환불 → revoke → 다음 PUT 시 403
 *
 * code-review ARCH-003: 비즈니스 로직 + 트랜잭션 경계는 `PlacementService` 로 이동.
 * 본 controller 는 인증 추출 + service 위임 + 응답 조립만.
 *
 * 좌표계 (2026-06-02): API 는 **0~1 비율(posX/posY)** 로 통신 — 캔버스 해상도 독립.
 * 저장은 기존 free_x/y_pixel(Int) 컬럼을 permille(0~10000) 로 재사용 (PlacementService).
 */
@RestController
@RequestMapping("/api/v1/terrarium/free-placement")
class PlacementController(
    private val placementService: PlacementService,
) {
    /** 현재 사용자의 모든 배치 아이템 + 자유 위치 (조회는 entitlement 불요 — preview 허용). */
    @GetMapping
    fun listFreePlacements(): ResponseEntity<FreePlacementListResponse> =
        ResponseEntity.ok(
            FreePlacementListResponse(
                items = placementService.listForUser(SecurityUtil.getCurrentUserId()),
            ),
        )

    @PutMapping("/{placementId}")
    fun updateFreePosition(
        @PathVariable placementId: Long,
        @RequestBody body: FreePositionRequest,
    ): ResponseEntity<FreePlacementItem> =
        ResponseEntity.ok(
            placementService.updateFreePosition(
                userId = SecurityUtil.getCurrentUserId(),
                placementId = placementId,
                posX = body.posX,
                posY = body.posY,
                scale = body.scale,
                flipped = body.flipped,
                zIndex = body.zIndex,
            ),
        )
}

/** 자유 위치 (0~1 비율, 캔버스 좌상단 기준) + 편집 상태(크기/반전/깊이, 미지정 시 기존값 유지). */
data class FreePositionRequest(
    val posX: Double,
    val posY: Double,
    val scale: Float? = null,
    val flipped: Boolean? = null,
    // Jackson single-lowercase-prefix quirk 회피(`zIndex`->`zindex`). 요청(역직렬화)이라
    // constructor param 에 명시(@param) — 계약 camelCase 고정.
    @param:JsonProperty("zIndex") val zIndex: Int? = null,
)

data class FreePlacementListResponse(
    val items: List<FreePlacementItem>,
)

data class FreePlacementItem(
    val placementId: Long,
    val itemId: Long,
    val itemName: String,
    val itemImage: String,
    val itemLayout: String,
    val posX: Double,
    val posY: Double,
    val isFreePlacement: Boolean,
    // 낙서장 자유배치 편집 영속(req3 #2): 크기/반전/깊이.
    val scale: Float,
    val flipped: Boolean,
    // Jackson single-lowercase-prefix quirk 회피(`zIndex`->`zindex`). 응답(직렬화)이라
    // getter 에 명시(생성 stub 컨벤션과 동일 @get:JsonProperty) — 계약 camelCase 고정.
    @get:JsonProperty("zIndex") val zIndex: Int,
)
