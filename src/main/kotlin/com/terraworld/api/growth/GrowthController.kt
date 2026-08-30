package com.terraworld.api.growth

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.GrowthApi
import io.terraworld.api.model.GrowthItem
import io.terraworld.api.model.GrowthResponse
import io.terraworld.api.model.GrowthReviveRequest
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * GrowthApi 구현 — 육성 개체 목록 조회 + 반짝이 부스터 + 아프젝 v2 되살리기/보류/알림 신청. 키우기 화면 백엔드 (낙서장 P3).
 */
@Tag(name = "Growth", description = "육성 API")
@RestController
@RequestMapping("/api/v1")
class GrowthController(
    private val growthService: GrowthService,
) : GrowthApi {
    @Operation(summary = "육성 개체 목록 조회", description = "정령/판타지식물 진행/단계/사이클 상태 (조회 시 LOST 판정·리셋 lazy 적용)")
    override fun getGrowth(): ResponseEntity<GrowthResponse> = ResponseEntity.ok(growthService.getGrowth(SecurityUtil.getCurrentUserId()))

    @Operation(summary = "육성 반짝이 부스터", description = "반짝이 100 소비 → 해당 종 +10회. LOST 사이클은 409 GROWTH_LOST")
    override fun buyGrowthBooster(speciesCode: String): ResponseEntity<GrowthItem> = ResponseEntity.ok(growthService.buyBooster(SecurityUtil.getCurrentUserId(), speciesCode))

    @Operation(
        summary = "되살리기",
        description = "LOST 사이클을 진행 유지한 채 ACTIVE 로 — RUBY 10 차감 또는 단계 정책을 통과한 광고 nonce 소비",
    )
    override fun reviveGrowth(
        speciesCode: String,
        @Valid growthReviveRequest: GrowthReviveRequest,
    ): ResponseEntity<GrowthItem> =
        ResponseEntity.ok(
            growthService.revive(
                SecurityUtil.getCurrentUserId(),
                speciesCode,
                growthReviveRequest.method,
                growthReviveRequest.adNonce,
            ),
        )

    @Operation(summary = "되살리기 보류", description = "팝업 닫기 — 당일 재시도 불가(409 GROWTH_REVIVE_SNOOZED), 다음날 새 사이클로 리셋")
    override fun dismissGrowthRevive(speciesCode: String): ResponseEntity<GrowthItem> = ResponseEntity.ok(growthService.dismissRevive(SecurityUtil.getCurrentUserId(), speciesCode))

    @Operation(summary = "정령 도착 알림 신청 토글", description = "완료 사이클 리셋 시 SPIRIT_ARRIVED 알림 1회 (리셋 후 자동 해제)")
    override fun toggleGrowthNotifyNext(speciesCode: String): ResponseEntity<GrowthItem> = ResponseEntity.ok(growthService.toggleNotifyNext(SecurityUtil.getCurrentUserId(), speciesCode))
}
