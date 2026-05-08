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
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * LevelApi (1 endpoint) implement. rewardType 은 generated 가 enum 으로
 * 강제 — DB 의 nullable String 을 [LevelConfigResponse.RewardType] 으로
 * 변환하되 unknown 값은 throw 회피 위해 null 로 처리하면서 **WARN 로그**
 * 를 남겨 spec ↔ DB drift 를 운영 가시화한다 (SF-001).
 */
@Tag(name = "Level", description = "레벨 조회 API")
@RestController
@RequestMapping("/api/v1")
class LevelController(
    private val levelConfigRepository: LevelConfigRepository,
    private val userRepository: UserRepository,
) : LevelApi {
    private val log = LoggerFactory.getLogger(javaClass)

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
                            rewardType = c.rewardType?.let { mapRewardTypeOrLogDrift(c.level, it) },
                            rewardValue = c.rewardValue,
                        )
                    },
            ),
        )
    }

    /**
     * DB 의 reward_type 문자열을 generated enum 으로 변환.
     * spec 에 없는 값이면 null 반환 + WARN 로그 (level 정보 포함). 운영 대시보드/
     * Sentry 가 본 로그를 수집해 spec drift 를 사전 감지할 수 있게 한다.
     */
    private fun mapRewardTypeOrLogDrift(
        level: Int,
        rewardType: String,
    ): LevelConfigResponse.RewardType? =
        runCatching { LevelConfigResponse.RewardType.forValue(rewardType) }
            .onFailure {
                log.warn(
                    "spec drift: level={} reward_type='{}' is not in OpenAPI spec — returning null (LevelConfigResponse.RewardType expected one of: {})",
                    level,
                    rewardType,
                    LevelConfigResponse.RewardType.entries.joinToString { it.value },
                )
            }.getOrNull()
}
