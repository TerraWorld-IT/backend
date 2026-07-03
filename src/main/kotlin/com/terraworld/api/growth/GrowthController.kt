package com.terraworld.api.growth

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.GrowthApi
import io.terraworld.api.model.GrowthItem
import io.terraworld.api.model.GrowthResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GrowthApi 구현 — 육성 개체 목록 조회 + 반짝이 부스터. 키우기 화면 백엔드 (낙서장 P3).
 */
@Tag(name = "Growth", description = "육성 API")
@RestController
@RequestMapping("/api/v1")
class GrowthController(
    private val growthService: GrowthService,
) : GrowthApi {
    @Operation(summary = "육성 개체 목록 조회", description = "정령/판타지식물 진행/단계/동면")
    override fun getGrowth(): ResponseEntity<GrowthResponse> = ResponseEntity.ok(growthService.getGrowth(SecurityUtil.getCurrentUserId()))

    @Operation(summary = "육성 반짝이 부스터", description = "반짝이 100 소비 → 해당 종 +10회")
    override fun buyGrowthBooster(speciesCode: String): ResponseEntity<GrowthItem> = ResponseEntity.ok(growthService.buyBooster(SecurityUtil.getCurrentUserId(), speciesCode))
}
