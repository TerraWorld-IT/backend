package com.terraworld.api.terrarium

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.TerrariumApi
import io.terraworld.api.model.HeartResponse
import io.terraworld.api.model.PlacementRequest
import io.terraworld.api.model.PlacementResponse
import io.terraworld.api.model.PlacementResponseItem
import io.terraworld.api.model.TerrariumBackgroundRequest
import io.terraworld.api.model.TerrariumResponse
import io.terraworld.api.model.TierCatalogResponse
import io.terraworld.api.model.TierUnlockRequest
import io.terraworld.api.model.TierUnlockResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * TerrariumApi (4 endpoints) implement.
 *
 * ARCH-008-phase-12 후: TerrariumService 가 generated DTO 직접 받음/반환.
 * controller 매퍼 0 — `updatePlacements` 만 spec 의 PlacementResponse 응답으로
 * 좁히는 변환 책임 (full TerrariumResponse → placedItems summary).
 */
@Tag(name = "Terrarium", description = "테라리움 API")
@RestController
@RequestMapping("/api/v1")
class TerrariumController(
    private val terrariumService: TerrariumService,
    private val tierService: TierService,
) : TerrariumApi {
    @Operation(summary = "테라리움 상태 조회")
    override fun getTerrarium(): ResponseEntity<TerrariumResponse> {
        val response = terrariumService.getTerrarium(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "아이템 배치 변경", description = "슬롯 규칙: 0,1=후경, 2,4=전경, 3=피규어")
    override fun updateTerrariumPlacements(
        @Valid placementRequest: PlacementRequest,
    ): ResponseEntity<PlacementResponse> {
        val terrarium = terrariumService.updatePlacements(SecurityUtil.getCurrentUserId(), placementRequest)
        return ResponseEntity.ok(
            PlacementResponse(
                placedItems =
                    terrarium.placedItems.map { detail ->
                        // SF-003: slotId=null 은 미배치 — service contract 위반 fail-fast
                        val resolvedSlotId =
                            checkNotNull(detail.slotId) {
                                "TerrariumService.updatePlacements 가 slotId=null 인 PlacedItem(itemId=${detail.itemId}) 을 반환했다 — service contract 위반"
                            }
                        PlacementResponseItem(
                            itemId = detail.itemId,
                            slotId = resolvedSlotId,
                            itemSlug = detail.itemSlug,
                        )
                    },
            ),
        )
    }

    @Operation(summary = "테라리움 배경 변경", description = "보유한 BACKGROUND 레이아웃 아이템으로 배경 변경 — 미보유/미존재 404, 레이아웃 불일치 409")
    override fun setTerrariumBackground(
        @Valid terrariumBackgroundRequest: TerrariumBackgroundRequest,
    ): ResponseEntity<TerrariumResponse> =
        ResponseEntity.ok(
            terrariumService.setBackground(SecurityUtil.getCurrentUserId(), terrariumBackgroundRequest.itemId),
        )

    @Operation(summary = "하트 클릭", description = "클릭당 기본코인 +1 지급 (Phase 4: DB BIGINT 정합)")
    override fun clickTerrariumHeart(): ResponseEntity<HeartResponse> {
        val response = terrariumService.heartClick(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "티어 카탈로그 조회", description = "낙서장 P2: 4단계 티어 해금 비용/슬롯/정령 + 현재 티어")
    override fun getTierCatalog(): ResponseEntity<TierCatalogResponse> = ResponseEntity.ok(tierService.getCatalog(SecurityUtil.getCurrentUserId()))

    @Operation(summary = "티어 해금", description = "낙서장 P2: 반짝이/루비 순차 해금 + 티어 정령 지급")
    override fun unlockTier(
        @Valid tierUnlockRequest: TierUnlockRequest,
    ): ResponseEntity<TierUnlockResponse> = ResponseEntity.ok(tierService.unlockTier(SecurityUtil.getCurrentUserId(), tierUnlockRequest.targetTier))
}
