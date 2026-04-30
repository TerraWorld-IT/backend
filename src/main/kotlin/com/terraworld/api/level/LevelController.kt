package com.terraworld.api.level

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Level", description = "레벨 조회 API")
@RestController
@RequestMapping("/api/v1/levels")
class LevelController(
    private val levelConfigRepository: LevelConfigRepository,
    private val userRepository: UserRepository,
) {
    @Operation(summary = "현재 레벨과 전체 레벨 구성 조회")
    @GetMapping
    fun getLevels(): LevelsResponse {
        val user =
            userRepository
                .findById(SecurityUtil.getCurrentUserId())
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val configs = levelConfigRepository.findAll().sortedBy { it.level }

        return LevelsResponse(
            currentLevel = user.level,
            currentExp = user.totalExp,
            levels =
                configs.map { c ->
                    LevelConfigPayload(
                        level = c.level,
                        requiredExp = c.requiredExp,
                        maxItems = c.maxItems,
                        rewardType = c.rewardType,
                        rewardValue = c.rewardValue,
                    )
                },
        )
    }
}

data class LevelsResponse(
    val currentLevel: Int,
    val currentExp: Long,
    val levels: List<LevelConfigPayload>,
)

data class LevelConfigPayload(
    val level: Int,
    val requiredExp: Long,
    val maxItems: Int,
    val rewardType: String?,
    val rewardValue: Int?,
)
