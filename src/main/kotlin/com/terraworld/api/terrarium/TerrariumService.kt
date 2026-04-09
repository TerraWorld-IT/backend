package com.terraworld.api.terrarium

import com.terraworld.api.terrarium.dto.*
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.*
import com.terraworld.domain.user.UserRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TerrariumService(
    private val terrariumRepository: TerrariumRepository,
    private val terrariumPlacementRepository: TerrariumPlacementRepository,
    private val userRepository: UserRepository,
    private val userItemRepository: UserItemRepository,
    private val itemRepository: ItemRepository,
    private val levelConfigRepository: LevelConfigRepository,
) {

    @Transactional(readOnly = true)
    fun getTerrarium(userId: Long): TerrariumResponse {
        val terrarium = terrariumRepository.findByUserId(userId)
            .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        val user = userRepository.findById(userId).orElseThrow()
        val maxItems = levelConfigRepository.findByLevel(user.level).map { it.maxItems }.orElse(10)

        return TerrariumResponse(
            terrariumId = terrarium.id,
            background = BackgroundInfo(
                id = terrarium.background.id,
                name = terrarium.background.name,
                assetUrl = terrarium.background.assetUrl,
            ),
            placedItems = terrarium.placedItems.map { p ->
                PlacedItemDetail(
                    id = p.id, itemId = p.item.id, itemSlug = p.item.slug,
                    itemImage = p.item.assetUrl, itemName = p.item.name,
                    itemLayout = p.item.layout.name, isAnimated = p.item.isAnimated,
                    slotId = p.slotId,
                )
            },
            maxSlots = minOf(5, maxItems),
        )
    }

    @Transactional
    fun updatePlacements(userId: Long, request: PlacementRequest): TerrariumResponse {
        val terrarium = terrariumRepository.findByUserId(userId)
            .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }

        // 슬롯 규칙 검증
        for (placement in request.placedItems) {
            val item = itemRepository.findById(placement.itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }

            if (!userItemRepository.existsByUserIdAndItemId(userId, item.id)) {
                throw BusinessException(ErrorCode.ITEM_NOT_FOUND, "보유하지 않은 아이템입니다")
            }

            val expectedLayout = when (placement.slotId) {
                0, 1 -> ItemLayout.BACKGROUND
                3 -> ItemLayout.FIGURE
                2, 4 -> ItemLayout.FOREGROUND
                else -> throw BusinessException(ErrorCode.INVALID_SLOT)
            }
            if (item.layout != expectedLayout) {
                throw BusinessException(ErrorCode.INVALID_SLOT)
            }
        }

        // 기존 배치 삭제 후 새로 배치
        terrariumPlacementRepository.deleteAllByTerrariumId(terrarium.id)
        terrarium.placedItems.clear()

        request.placedItems.forEach { p ->
            val item = itemRepository.findById(p.itemId).orElseThrow()
            terrarium.placedItems.add(
                TerrariumPlacement(terrarium = terrarium, item = item, slotId = p.slotId)
            )
        }
        terrariumRepository.save(terrarium)

        return getTerrarium(userId)
    }

    @Transactional
    fun heartClick(userId: Long): HeartResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        // +0.1 은 정수 기반에서 1로 처리 (x10 스케일링은 추후 논의)
        // 현재는 BIGINT라 +1로 처리
        user.basicCoin += 1
        userRepository.save(user)
        return HeartResponse(reward = 0.1, updatedBasicCoins = user.basicCoin.toDouble())
    }
}
