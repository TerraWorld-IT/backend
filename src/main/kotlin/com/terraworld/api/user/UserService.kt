package com.terraworld.api.user

import com.terraworld.api.user.dto.*
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class UserService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val userItemRepository: UserItemRepository,
    private val terrariumRepository: TerrariumRepository,
    private val levelConfigRepository: LevelConfigRepository,
    private val currencyBuilder: CurrencyBuilder,
) {
    fun getMe(
        userId: String,
        email: String,
    ): UserMeResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val nextLevelConfig = levelConfigRepository.findByLevel(user.level + 1)
        val expToNext = nextLevelConfig.map { it.requiredExp - user.totalExp }.orElse(0L)

        val ownedItems =
            userItemRepository
                .findAllByUserId(userId)
                .mapNotNull { it.item.slug }

        val terrarium = terrariumRepository.findByUserId(userId)
        val placedItems =
            terrarium
                .map { t ->
                    t.placedItems.map { p ->
                        PlacedItemResponse(
                            itemId = p.item.id,
                            itemSlug = p.item.slug,
                            slotId = p.slotId,
                        )
                    }
                }.orElse(emptyList())

        return UserMeResponse(
            userId = user.id,
            email = email,
            nickname = user.nickname,
            role = user.role.name,
            currency = currencyBuilder.build(userId, user),
            progress =
                ProgressResponse(
                    level = user.level,
                    experience = user.totalExp,
                    experienceToNext = expToNext,
                ),
            ownedItems = ownedItems,
            placedItems = placedItems,
            entitlements =
                EntitlementsResponse(
                    freePlacement = user.entitlementFreePlacement,
                    premiumThemes = user.entitlementPremiumThemes,
                ),
        )
    }
}
