package com.terraworld.api.terrarium

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.TerrariumApi
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.terrarium.dto.BackgroundInfo as LocalBackgroundInfo
import com.terraworld.api.terrarium.dto.HeartResponse as LocalHeartResponse
import com.terraworld.api.terrarium.dto.PlacedItemDetail as LocalPlacedItemDetail
import com.terraworld.api.terrarium.dto.PlacementItem as LocalPlacementItem
import com.terraworld.api.terrarium.dto.PlacementRequest as LocalPlacementRequest
import com.terraworld.api.terrarium.dto.TerrariumResponse as LocalTerrariumResponse
import com.terraworld.api.terrarium.dto.UpgradeTerrariumRequest as LocalUpgradeTerrariumRequest
import com.terraworld.api.terrarium.dto.WiltingState as LocalWiltingState
import io.terraworld.api.model.BackgroundInfo as ApiBackgroundInfo
import io.terraworld.api.model.EvolutionStage as ApiEvolutionStage
import io.terraworld.api.model.HeartResponse as ApiHeartResponse
import io.terraworld.api.model.PlacedItemDetail as ApiPlacedItemDetail
import io.terraworld.api.model.PlacementRequest as ApiPlacementRequest
import io.terraworld.api.model.PlacementResponse as ApiPlacementResponse
import io.terraworld.api.model.PlacementResponseItem as ApiPlacementResponseItem
import io.terraworld.api.model.TerrariumResponse as ApiTerrariumResponse
import io.terraworld.api.model.UpgradeTerrariumRequest as ApiUpgradeTerrariumRequest
import io.terraworld.api.model.UpgradeTerrariumResponse as ApiUpgradeTerrariumResponse
import io.terraworld.api.model.WiltingState as ApiWiltingState

/**
 * TerrariumApi (4 endpoints) implement. service 시그니처는 그대로 두고
 * controller 에서 generated DTO 로 변환.
 *
 * `updatePlacements` 의 spec 응답이 `TerrariumResponse` (full snapshot) →
 * `PlacementResponse` (placedItems summary 만) 으로 좁혀졌으므로
 * controller 에서 service 의 TerrariumResponse 결과의 placedItems 만
 * 추출해 PlacementResponse 로 매핑한다.
 *
 * EvolutionStage 는 generated 가 enum (POT/BOTTLE/PALUDARIUM/WORLD/CUSTOM)
 * 으로 강제 — service 의 String 을 forValue 로 변환. unknown 값은
 * runCatching 으로 null 처리.
 */
@Tag(name = "Terrarium", description = "테라리움 API")
@RestController
@RequestMapping("/api/v1")
class TerrariumController(
    private val terrariumService: TerrariumService,
) : TerrariumApi {
    @Operation(summary = "테라리움 상태 조회")
    override fun getTerrarium(): ResponseEntity<ApiTerrariumResponse> {
        val local = terrariumService.getTerrarium(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(local.toApi())
    }

    @Operation(summary = "아이템 배치 변경", description = "슬롯 규칙: 0,1=후경, 2,4=전경, 3=피규어")
    override fun updateTerrariumPlacements(
        @Valid placementRequest: ApiPlacementRequest,
    ): ResponseEntity<ApiPlacementResponse> {
        val local =
            terrariumService.updatePlacements(
                userId = SecurityUtil.getCurrentUserId(),
                request =
                    LocalPlacementRequest(
                        placedItems =
                            placementRequest.placedItems.map {
                                LocalPlacementItem(
                                    itemId = it.itemId,
                                    slotId = it.slotId,
                                )
                            },
                    ),
            )
        return ResponseEntity.ok(
            ApiPlacementResponse(
                placedItems =
                    local.placedItems.map { detail ->
                        ApiPlacementResponseItem(
                            itemId = detail.itemId,
                            slotId = detail.slotId ?: 0,
                            itemSlug = detail.itemSlug,
                        )
                    },
            ),
        )
    }

    @Operation(summary = "하트 클릭", description = "클릭당 기본코인 +0.1 지급")
    override fun clickTerrariumHeart(): ResponseEntity<ApiHeartResponse> {
        val local = terrariumService.heartClick(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(local.toApi())
    }

    @Operation(summary = "진화 단계 전환", description = "잠금 해제된 단계 사이에서만 전환 가능")
    override fun upgradeTerrarium(
        @Valid upgradeTerrariumRequest: ApiUpgradeTerrariumRequest,
    ): ResponseEntity<ApiUpgradeTerrariumResponse> {
        val local =
            terrariumService.upgrade(
                userId = SecurityUtil.getCurrentUserId(),
                request = LocalUpgradeTerrariumRequest(targetStage = upgradeTerrariumRequest.targetStage.value),
            )
        return ResponseEntity.ok(
            ApiUpgradeTerrariumResponse(
                terrarium = local.terrarium.toApi(),
                message = local.message,
            ),
        )
    }

    private fun LocalTerrariumResponse.toApi(): ApiTerrariumResponse =
        ApiTerrariumResponse(
            terrariumId = terrariumId,
            background = background.toApi(),
            placedItems = placedItems.map { it.toApi() },
            maxSlots = maxSlots,
            evolutionStage = evolutionStage.toEvolutionStageOrNull(),
            unlockedStages = unlockedStages.mapNotNull { it.toEvolutionStageOrNull() },
            wilting = wilting?.toApi(),
        )

    private fun LocalBackgroundInfo.toApi(): ApiBackgroundInfo =
        ApiBackgroundInfo(
            id = id,
            name = name,
            assetUrl = assetUrl,
        )

    private fun LocalPlacedItemDetail.toApi(): ApiPlacedItemDetail =
        ApiPlacedItemDetail(
            id = id,
            itemId = itemId,
            itemImage = itemImage,
            itemName = itemName,
            // unknown layout 은 generated forValue 가 throw 하므로 안전 변환.
            // FOREGROUND/BACKGROUND/FIGURE 외 값이 들어오면 컴파일 fail 하지 못하지만,
            // domain enum (ItemLayout) 의 .name 을 사용하므로 값은 항상 유효.
            itemLayout = ApiPlacedItemDetail.ItemLayout.forValue(itemLayout),
            isAnimated = isAnimated,
            itemSlug = itemSlug,
            slotId = slotId,
        )

    private fun LocalWiltingState.toApi(): ApiWiltingState =
        ApiWiltingState(
            stage = stage,
            daysSinceRecord = daysSinceRecord,
        )

    private fun LocalHeartResponse.toApi(): ApiHeartResponse =
        ApiHeartResponse(
            reward = reward,
            updatedBasicCoins = updatedBasicCoins,
        )

    /**
     * service 의 String evolutionStage 를 generated enum 으로 매핑.
     * 알 수 없는 값은 forValue 의 throw 회피 위해 null 반환.
     */
    private fun String.toEvolutionStageOrNull(): ApiEvolutionStage? = runCatching { ApiEvolutionStage.forValue(this) }.getOrNull()
}
