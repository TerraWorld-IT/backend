package com.terraworld.api.terrarium

import com.terraworld.api.terrarium.dto.*
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.*
import com.terraworld.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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
) {
    companion object {
        // 시들기 단계 임계치 (마지막 기록 이후 경과 일수 기준)
        private const val WILT_STAGE_1_DAYS = 3L // 시들기 시작
        private const val WILT_STAGE_2_DAYS = 7L // 많이 시듦
        private const val WILT_STAGE_3_DAYS = 14L // 위기
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
                        itemSlug = p.item.slug,
                        itemImage = p.item.assetUrl,
                        itemName = p.item.name,
                        itemLayout = p.item.layout.name,
                        isAnimated = p.item.isAnimated,
                        slotId = p.slotId,
                    )
                },
            maxSlots = minOf(5, maxItems),
            evolutionStage = terrarium.evolutionStage.name,
            unlockedStages = unlocked.map { it.name },
            wilting = computeWiltingState(userId),
        )
    }

    /**
     * 진화 단계 전환. 잠금 해제된 단계만 허용. CUSTOM 은 freePlacement entitlement 추가 필요.
     * 다운그레이드는 허용하되 잠금 해제 범위 안에서만.
     */
    @Transactional
    fun upgrade(
        userId: String,
        request: UpgradeTerrariumRequest,
    ): UpgradeTerrariumResponse {
        val target =
            runCatching { EvolutionStage.valueOf(request.targetStage) }
                .getOrElse { throw BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 진화 단계: ${request.targetStage}") }

        val user = userRepository.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val unlocked = EvolutionStage.unlockedFor(user.level)
        if (target !in unlocked) {
            throw BusinessException(ErrorCode.FORBIDDEN_EVOLUTION, "레벨이 부족합니다 (필요: ${target.unlockLevel})")
        }
        if (target == EvolutionStage.CUSTOM && !user.entitlementFreePlacement) {
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
     * 시들기 상태를 activity_records 의 최신 recorded_date 기준으로 동적 계산.
     * - 기록 없음 → stage 0, daysSinceRecord null
     * - 0~2 일 → stage 0 (정상)
     * - 3~6 일 → stage 1 (시들기 시작)
     * - 7~13 일 → stage 2 (많이 시듦)
     * - 14 일 이상 → stage 3 (위기)
     * 추후 P-4(FE 정적 연출) 에서 stage 를 CSS 채도/말풍선에 매핑한다.
     */
    private fun computeWiltingState(userId: String): WiltingState {
        val latest =
            recordRepository.findMaxRecordedDate(userId)
                ?: return WiltingState(stage = 0, daysSinceRecord = null)
        val days = ChronoUnit.DAYS.between(latest, LocalDate.now())
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

        // 슬롯 규칙 검증
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

        // 기존 배치 itemId 집합 — 새 itemId 만 history 에 추가 (decoration 랭킹 인플레이션 방지)
        val existingItemIds = terrarium.placedItems.map { it.item.id }.toSet()

        // 기존 배치 삭제 후 새로 배치
        terrariumPlacementRepository.deleteAllByTerrariumId(terrarium.id)
        terrarium.placedItems.clear()

        request.placedItems.forEach { p ->
            val item = itemRepository.findById(p.itemId).orElseThrow()
            terrarium.placedItems.add(
                TerrariumPlacement(terrarium = terrarium, item = item, slotId = p.slotId),
            )
            // 새로 등장한 itemId 만 history 에 적재 (월간 신규 배치 카운트용)
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
        // +0.1 은 정수 기반에서 1로 처리 (x10 스케일링은 추후 논의)
        // 현재는 BIGINT라 +1로 처리
        user.basicCoin += 1
        userRepository.save(user)
        return HeartResponse(reward = 0.1, updatedBasicCoins = user.basicCoin.toDouble())
    }
}
