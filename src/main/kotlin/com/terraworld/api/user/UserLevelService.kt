package com.terraworld.api.user

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.EvolutionStage
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * UltraPlan v3 J-EVO-001 (2026-05-18, Codex pre-audit HIGH):
 *
 * EXP 누적 → level 자동 갱신. RecordService.kt:166 의 totalExp += 10 직후 호출.
 *
 * 곡선 (UltraPlan v3 §4.2.A 권장 default):
 *  - 선형 N × 100 (level 1→2 = 100, level 2→3 = 200 누적, level 5 = 500)
 *  - V12 seed 의 10 단계까지 (Codex post-audit MEDIUM 3 — 상한 10)
 *  - Decay 없음 — 광고 복구 / 환불 / 시들기 모두 totalExp 변동 X
 *
 * 동작:
 *  1) level_config 모든 row 조회 (10 row)
 *  2) user.totalExp >= cfg.requiredExp 만족하는 가장 높은 level 찾음
 *  3) 현재 user.level 보다 높으면 갱신 + AuditService LEVEL_UP 기록 + 반환
 *  4) 동일 또는 낮으면 no-op (0 반환)
 */
@Service
class UserLevelService(
    private val userRepository: UserRepository,
    private val levelConfigRepository: LevelConfigRepository,
    private val terrariumRepository: TerrariumRepository,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * EXP 가산 후 호출. level 자동 증가 시 증가량 (delta) 반환.
     *
     * @return 0 = level 변동 X / 1+ = level delta (보통 1, 큰 EXP 가산 시 2+)
     */
    @Transactional
    fun checkAndApplyLevelUp(user: User): Int {
        val configs = levelConfigRepository.findAll().sortedBy { it.level }
        if (configs.isEmpty()) {
            log.warn("level.config.empty user={} — V12 seed 미적용 가능성", user.id)
            return 0
        }

        // user.totalExp 가 만족하는 가장 높은 level 찾기
        val targetLevel = configs.filter { user.totalExp >= it.requiredExp }.maxOfOrNull { it.level } ?: 1

        if (targetLevel <= user.level) {
            return 0
        }

        val oldLevel = user.level
        user.level = targetLevel
        userRepository.save(user)

        val delta = targetLevel - oldLevel
        auditService.publish(
            userId = user.id,
            action = "LEVEL_UP",
            resourceType = "User",
            payload =
                mapOf(
                    "from" to oldLevel,
                    "to" to targetLevel,
                    "totalExp" to user.totalExp,
                ),
        )
        log.info("level.up user={} {}→{} totalExp={}", user.id, oldLevel, targetLevel, user.totalExp)

        // UltraPlan v3 P-EVOLVE-001 (2026-05-18):
        // level-up 으로 신규 EvolutionStage unlock 시 terrarium.evolutionUnlockedAt 갱신.
        // frontend 의 UpgradeModal (모달 G) 가 본 timestamp + currentStage 비교로 표시 결정.
        // (예: oldLevel=4 → unlocked=[POT,BOTTLE] / targetLevel=5 → unlocked=[POT,BOTTLE] 동일 → 갱신 안 함.
        //      oldLevel=9 → unlocked=[POT,BOTTLE,PALUDARIUM] / targetLevel=10 → unlocked=[POT,BOTTLE,PALUDARIUM] 동일.
        //      oldLevel=4 → unlocked=[POT,BOTTLE] / targetLevel=10 → unlocked=[POT,BOTTLE,PALUDARIUM] → 1 신규 → 갱신.)
        val oldUnlocked = EvolutionStage.unlockedFor(oldLevel).toSet()
        val newUnlocked = EvolutionStage.unlockedFor(targetLevel).toSet()
        if (newUnlocked.size > oldUnlocked.size) {
            terrariumRepository.findByUserId(user.id).ifPresent { terrarium ->
                terrarium.evolutionUnlockedAt = LocalDateTime.now()
                terrarium.updatedAt = LocalDateTime.now()
                terrariumRepository.save(terrarium)
                log.info(
                    "evolution.unlock user={} unlocked={} (oldLevel={}, newLevel={})",
                    user.id,
                    newUnlocked.minus(oldUnlocked).map { it.name },
                    oldLevel,
                    targetLevel,
                )
            }
        }

        return delta
    }
}
