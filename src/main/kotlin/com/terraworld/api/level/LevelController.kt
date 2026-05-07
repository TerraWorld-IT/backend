package com.terraworld.api.level

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.LevelApi
import io.terraworld.api.model.LevelConfigResponse
import io.terraworld.api.model.LevelsResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * LevelApi (1 endpoint) implement. local DTO 들 (LevelsResponse,
 * LevelConfigPayload) 제거하고 generated 직접 사용. rewardType 은 generated
 * 가 enum 으로 강제 — DB 의 nullable String 을 LevelConfigResponse.RewardType
 * 으로 변환하되 unknown 값은 forValue 가 throw 하므로 nullable 우회.
 */
@Tag(name = "Level", description = "레벨 조회 API")
@RestController
@RequestMapping("/api/v1")
class LevelController(
    private val levelConfigRepository: LevelConfigRepository,
    private val userRepository: UserRepository,
) : LevelApi {
    @Operation(summary = "현재 레벨과 전체 레벨 구성 조회")
    override fun getLevels(): ResponseEntity<LevelsResponse> {
        val user =
            userRepository
                .findById(SecurityUtil.getCurrentUserId())
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val configs = levelConfigRepository.findAll().sortedBy { it.level }

        return ResponseEntity.ok(
            LevelsResponse(
                currentLevel = user.level,
                currentExp = user.totalExp,
                levels =
                    configs.map { c ->
                        LevelConfigResponse(
                            level = c.level,
                            requiredExp = c.requiredExp,
                            maxItems = c.maxItems,
                            // DB 에는 임의 문자열이 들어올 수 있으므로 known enum value 만 매핑.
                            // 알 수 없는 값은 null 로 보낸다 (RewardType.forValue 의 throw 회피).
                            rewardType =
                                c.rewardType?.let { type ->
                                    runCatching { LevelConfigResponse.RewardType.forValue(type) }.getOrNull()
                                },
                            rewardValue = c.rewardValue,
                        )
                    },
            ),
        )
    }
}
