package com.terraworld.api.terrarium

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.TerrariumApi
import io.terraworld.api.model.HeartResponse
import io.terraworld.api.model.PlacementRequest
import io.terraworld.api.model.PlacementResponse
import io.terraworld.api.model.PlacementResponseItem
import io.terraworld.api.model.TerrariumResponse
import io.terraworld.api.model.UpgradeTerrariumRequest
import io.terraworld.api.model.UpgradeTerrariumResponse
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

    @Operation(summary = "하트 클릭", description = "클릭당 기본코인 +0.1 지급")
    override fun clickTerrariumHeart(): ResponseEntity<HeartResponse> {
        val response = terrariumService.heartClick(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "진화 단계 전환", description = "잠금 해제된 단계 사이에서만 전환 가능")
    override fun upgradeTerrarium(
        @Valid upgradeTerrariumRequest: UpgradeTerrariumRequest,
    ): ResponseEntity<UpgradeTerrariumResponse> {
        val response =
            terrariumService.upgrade(SecurityUtil.getCurrentUserId(), upgradeTerrariumRequest)
        return ResponseEntity.ok(response)
    }
}
