package com.terraworld.api.terrarium

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.EvolutionStage
import com.terraworld.domain.terrarium.TerrariumPlacement
import com.terraworld.domain.terrarium.TerrariumPlacementHistory
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.BackgroundInfo
import io.terraworld.api.model.HeartResponse
import io.terraworld.api.model.PlacedItemDetail
import io.terraworld.api.model.PlacementRequest
import io.terraworld.api.model.TerrariumResponse
import io.terraworld.api.model.UpgradeTerrariumRequest
import io.terraworld.api.model.UpgradeTerrariumResponse
import io.terraworld.api.model.WiltingState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit
import io.terraworld.api.model.EvolutionStage as ApiEvolutionStage

/**
 * ARCH-008-phase-12: 시그니처 + 반환 모두 generated DTO 사용.
 *
 * service 가 enum 변환 책임 흡수:
 *  - domain `EvolutionStage` → generated `ApiEvolutionStage` (ADR-019 warn log + null)
 *  - domain `ItemLayout` → generated `PlacedItemDetail.ItemLayout` (forValue, hardcoded 안전)
 */
@Service
class TerrariumService(
    private val terrariumRepository: TerrariumRepository,
    private val terrariumPlacementRepository: TerrariumPlacementRepository,
    private val userRepository: UserRepository,
    private val userItemRepository: UserItemRepository,
    private val itemRepository: ItemRepository,
    private val levelConfigRepository: LevelConfigRepository,
    private val recordRepository: RecordRepository,
    private val placementHistoryRepository: TerrariumPlacementHistoryRepository,
    private val entitlementService: com.terraworld.api.entitlement.EntitlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 시들기 단계 임계치 (마지막 기록 이후 경과 일수 기준)
        private const val WILT_STAGE_1_DAYS = 3L
        private const val WILT_STAGE_2_DAYS = 7L
        private const val WILT_STAGE_3_DAYS = 14L
    }

    @Transactional(readOnly = true)
    fun getTerrarium(userId: String): TerrariumResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        val user = userRepository.findById(userId).orElseThrow()
        val maxItems = levelConfigRepository.findByLevel(user.level).map { it.maxItems }.orElse(10)
        val unlocked = EvolutionStage.unlockedFor(user.level)

        return TerrariumResponse(
            terrariumId = terrarium.id,
            background =
                BackgroundInfo(
                    id = terrarium.background.id,
                    name = terrarium.background.name,
                    assetUrl = terrarium.background.assetUrl,
                ),
            placedItems =
                terrarium.placedItems.map { p ->
                    PlacedItemDetail(
                        id = p.id,
                        itemId = p.item.id,
                        itemImage = p.item.assetUrl,
                        itemName = p.item.name,
                        itemLayout = PlacedItemDetail.ItemLayout.forValue(p.item.layout.name),
                        isAnimated = p.item.isAnimated,
                        itemSlug = p.item.slug,
                        slotId = p.slotId,
                    )
                },
            maxSlots = minOf(5, maxItems),
            evolutionStage = terrarium.evolutionStage.name.toApiEvolutionStageOrNull(),
            unlockedStages = unlocked.mapNotNull { it.name.toApiEvolutionStageOrNull() },
            wilting = computeWiltingState(userId),
        )
    }

    @Transactional
    fun upgrade(
        userId: String,
        request: UpgradeTerrariumRequest,
    ): UpgradeTerrariumResponse {
        val target =
            runCatching { EvolutionStage.valueOf(request.targetStage.value) }
                .getOrElse { throw BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 진화 단계: ${request.targetStage}") }

        val user = userRepository.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val unlocked = EvolutionStage.unlockedFor(user.level)
        if (target !in unlocked) {
            throw BusinessException(ErrorCode.FORBIDDEN_EVOLUTION, "레벨이 부족합니다 (필요: ${target.unlockLevel})")
        }
        // N3 (구현 계획서 v4): entitlement SoT = user_entitlement 테이블.
        // 이전 user.entitlementFreePlacement boolean 은 deprecated — EntitlementService 가
        // grant/revoke 의 단일 SoT 이므로 IAP webhook 이 갱신하는 테이블을 직접 조회.
        if (target == EvolutionStage.CUSTOM &&
            !entitlementService.hasEntitlement(userId, com.terraworld.domain.entitlement.UserEntitlementId.FREE_PLACEMENT)
        ) {
            throw BusinessException(ErrorCode.FORBIDDEN_EVOLUTION, "자유배치 권리(freePlacement)가 필요합니다")
        }

        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        val previous = terrarium.evolutionStage
        terrarium.evolutionStage = target
        terrariumRepository.save(terrarium)

        val message =
            if (previous == target) {
                "이미 해당 단계입니다"
            } else if (target.unlockLevel > previous.unlockLevel) {
                "${target.name} 으로 진화했습니다"
            } else {
                "${target.name} 으로 변경했습니다"
            }
        return UpgradeTerrariumResponse(terrarium = getTerrarium(userId), message = message)
    }

    /**
     * P-WILT-001 + J-WILT-001 (UltraPlan v3, 2026-05-18):
     *
     * 시들기 단계는 max(마지막 record date, terrarium.wiltRecoveredAt) 기준의
     * 경과 일수로 계산. RewardService.claimAdReward 가 wiltRecoveredAt 을 now() 로
     * 갱신하면 본 함수가 stage 0 으로 자동 reset (Codex pre-audit J-WILT 해결).
     *
     * 둘 다 null = 기록 없는 신규 사용자 → stage 0 / daysSinceRecord null.
     */
    private fun computeWiltingState(userId: String): WiltingState {
        val lastRecordDate = recordRepository.findMaxRecordedDate(userId)
        val wiltRecoveredDate =
            terrariumRepository
                .findByUserId(userId)
                .map { it.wiltRecoveredAt?.toLocalDate() }
                .orElse(null)

        val effective =
            listOfNotNull(lastRecordDate, wiltRecoveredDate).maxOrNull()
                ?: return WiltingState(stage = 0, daysSinceRecord = null)

        val days = ChronoUnit.DAYS.between(effective, KstTime.today())
        val stage =
            when {
                days >= WILT_STAGE_3_DAYS -> 3
                days >= WILT_STAGE_2_DAYS -> 2
                days >= WILT_STAGE_1_DAYS -> 1
                else -> 0
            }
        return WiltingState(stage = stage, daysSinceRecord = days.toInt())
    }

    @Transactional
    fun updatePlacements(
        userId: String,
        request: PlacementRequest,
    ): TerrariumResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }

        for (placement in request.placedItems) {
            val item =
                itemRepository
                    .findById(placement.itemId)
                    .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }

            if (!userItemRepository.existsByUserIdAndItemId(userId, item.id)) {
                throw BusinessException(ErrorCode.ITEM_NOT_FOUND, "보유하지 않은 아이템입니다")
            }

            val expectedLayout =
                when (placement.slotId) {
                    0, 1 -> ItemLayout.BACKGROUND
                    3 -> ItemLayout.FIGURE
                    2, 4 -> ItemLayout.FOREGROUND
                    else -> throw BusinessException(ErrorCode.INVALID_SLOT)
                }
            if (item.layout != expectedLayout) {
                throw BusinessException(ErrorCode.INVALID_SLOT)
            }
        }

        val existingItemIds = terrarium.placedItems.map { it.item.id }.toSet()

        terrariumPlacementRepository.deleteAllByTerrariumId(terrarium.id)
        terrarium.placedItems.clear()

        request.placedItems.forEach { p ->
            val item = itemRepository.findById(p.itemId).orElseThrow()
            terrarium.placedItems.add(
                TerrariumPlacement(terrarium = terrarium, item = item, slotId = p.slotId),
            )
            if (item.id !in existingItemIds) {
                placementHistoryRepository.save(
                    TerrariumPlacementHistory(
                        userId = userId,
                        itemId = item.id,
                        slotId = p.slotId,
                    ),
                )
            }
        }
        terrariumRepository.save(terrarium)

        return getTerrarium(userId)
    }

    @Transactional
    fun heartClick(userId: String): HeartResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        // /analyze 2026-05-18 발견 (Codex C-1): 기존 코드 `basicCoin += 1` ↔ `reward = 0.1`
        // 10× mismatch — response 가 실제 DB 누적과 불일치. spec example 도 +0.1 였으나
        // DB 컬럼 BIGINT 와 fractional 모델 충돌. autonomous default (Phase 4 pre-launch):
        //   - DB schema 유지 (BIGINT)
        //   - response 의 `reward` 와 `updatedBasicCoins` 가 실 DB state 와 정확히 일치
        //   - spec example 도 1.0 으로 정정 (별 PR)
        // 어뷰징 cooldown 정책은 별 cycle (현재 client side throttle 만 존재).
        val rewardCoins = 1L
        user.basicCoin += rewardCoins
        userRepository.save(user)
        return HeartResponse(
            reward = rewardCoins.toDouble(),
            updatedBasicCoins = user.basicCoin.toDouble(),
        )
    }

    /**
     * domain enum 의 .name 을 generated [ApiEvolutionStage] 로 변환.
     * spec 에 없는 값이면 null + WARN (ADR-019 SF-002 패턴).
     */
    private fun String.toApiEvolutionStageOrNull(): ApiEvolutionStage? =
        runCatching { ApiEvolutionStage.forValue(this) }
            .onFailure {
                log.warn(
                    "spec drift: evolution_stage='{}' is not in OpenAPI spec — returning null (expected one of: {})",
                    this,
                    ApiEvolutionStage.entries.joinToString { it.value },
                )
            }.getOrNull()
}
