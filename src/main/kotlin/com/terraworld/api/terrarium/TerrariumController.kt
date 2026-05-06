package com.terraworld.api.terrarium

import com.terraworld.api.terrarium.dto.*
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.web.bind.annotation.*

@Tag(name = "Terrarium", description = "테라리움 API")
@RestController
@RequestMapping("/api/v1/terrarium")
class TerrariumController(
    private val terrariumService: TerrariumService,
) {
    @Operation(summary = "테라리움 상태 조회")
    @GetMapping
    fun getTerrarium(): TerrariumResponse = terrariumService.getTerrarium(SecurityUtil.getCurrentUserId())

    @Operation(summary = "아이템 배치 변경", description = "슬롯 규칙: 0,1=후경, 2,4=전경, 3=피규어")
    @PutMapping("/placements")
    fun updatePlacements(
        @Valid @RequestBody request: PlacementRequest,
    ): TerrariumResponse = terrariumService.updatePlacements(SecurityUtil.getCurrentUserId(), request)

    @Operation(summary = "하트 클릭", description = "클릭당 기본코인 +0.1 지급")
    @PostMapping("/heart")
    fun heartClick(): HeartResponse = terrariumService.heartClick(SecurityUtil.getCurrentUserId())

    @Operation(summary = "진화 단계 전환", description = "잠금 해제된 단계 사이에서만 전환 가능")
    @PostMapping("/upgrade")
    fun upgrade(
        @Valid @RequestBody request: UpgradeTerrariumRequest,
    ): UpgradeTerrariumResponse = terrariumService.upgrade(SecurityUtil.getCurrentUserId(), request)
}
