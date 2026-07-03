package com.terraworld.api.growth

import com.terraworld.api.currency.CurrencyService
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.growth.GrowthInstance
import com.terraworld.domain.growth.GrowthInstanceRepository
import com.terraworld.domain.growth.GrowthSpeciesRepository
import com.terraworld.domain.growth.GrowthStage
import com.terraworld.domain.growth.GrowthStageRepository
import io.terraworld.api.model.GrowthItem
import io.terraworld.api.model.GrowthResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.temporal.ChronoUnit

/**
 * 육성 상태 조회 + mutation (낙서장 P3).
 * - read: 종별 유효진행(자연 연속 + 반짝이 구매분) / 현재 단계 / 동면 여부.
 * - advanceAllStreaks: 일상 기록 시 전 종 자연 연속 진행(하루 1회, 공백 시 실패=streak 0 재시작·반짝이 유지 §3-8).
 * - buyBooster: 반짝이 100 소비 → 해당 종 +10회(부스터). 실패해도 유지.
 * - dormancy(P0.5): 마지막 진행 이후 3일+ 미기록 = 동면(read 시 lazy 계산).
 */
@Service
class GrowthService(
    private val growthSpeciesRepository: GrowthSpeciesRepository,
    private val growthStageRepository: GrowthStageRepository,
    private val growthInstanceRepository: GrowthInstanceRepository,
    private val currencyService: CurrencyService,
) {
    companion object {
        const val SPARKLE_PER_BOOSTER = 100L
        const val BOOSTER_PROGRESS = 10
        const val DORMANCY_DAYS = 3L
        const val REASON_GROW_BOOST = "GROW_BOOST"
        const val REF_TYPE = "GROWTH"
    }

    @Transactional(readOnly = true)
    fun getGrowth(userId: String): GrowthResponse {
        val instances = growthInstanceRepository.findAllByUserId(userId).associateBy { it.speciesCode }
        val today = KstTime.today()
        val items =
            growthSpeciesRepository.findAllByOrderBySortOrderAsc().map { species ->
                val inst = instances[species.code]
                val stages = growthStageRepository.findAllByKindOrderByStageOrderAsc(species.kind)
                val goal = stages.maxOfOrNull { it.threshold } ?: 30
                val effectiveProgress = (inst?.naturalStreak ?: 0) + (inst?.sparkleBoughtCount ?: 0)
                val currentStage = stageForProgress(stages, effectiveProgress)
                val stageLabel = stages.firstOrNull { it.stageOrder == currentStage }?.labelKo ?: ""
                // P0.5 dormancy: 마지막 진행 이후 3일+ 미기록 = 동면 (lazy)
                val dormant =
                    inst?.lastProgressAt?.let {
                        ChronoUnit.DAYS.between(it.toLocalDate(), today) >= DORMANCY_DAYS
                    } ?: false
                GrowthItem(
                    speciesCode = species.code,
                    kind = GrowthItem.Kind.forValue(species.kind),
                    nameKo = species.nameKo,
                    currentStage = currentStage,
                    stageLabel = stageLabel,
                    effectiveProgress = effectiveProgress,
                    goal = goal,
                    dormant = dormant,
                )
            }
        return GrowthResponse(items = items)
    }

    /**
     * 일상 기록 시 전 종 자연 연속 진행 (하루 1회). 연속이면 streak++, 공백(1일+)이면 실패 → streak=1 재시작
     * (sparkleBoughtCount 는 유지 §3-8). 개체 미생성 종은 생성. RecordService.createRecord hook.
     */
    @Transactional
    fun advanceAllStreaks(userId: String) {
        val today = KstTime.today()
        val existing = growthInstanceRepository.findAllByUserId(userId).associateBy { it.speciesCode }
        val stagesByKind = mutableMapOf<String, List<GrowthStage>>()
        val now = KstTime.now() // 리뷰 S/CDX: 저장·비교 모두 KST (JVM UTC 혼용 방지)

        // 리뷰 Q#7: 전 종 개별 save 대신 batch saveAll
        val toSave =
            growthSpeciesRepository.findAllByOrderBySortOrderAsc().mapNotNull { species ->
                val inst =
                    existing[species.code] ?: GrowthInstance(userId = userId, speciesCode = species.code)
                val lastDate = inst.lastProgressAt?.toLocalDate()
                if (lastDate == today) return@mapNotNull null // 오늘 이미 진행 — 멱등

                inst.naturalStreak =
                    if (lastDate == today.minusDays(1)) inst.naturalStreak + 1 else 1 // 연속 or 실패 재시작
                inst.lastProgressAt = now
                val stages = stagesByKind.getOrPut(species.kind) { growthStageRepository.findAllByKindOrderByStageOrderAsc(species.kind) }
                inst.currentStage = stageForProgress(stages, inst.naturalStreak + inst.sparkleBoughtCount)
                inst
            }
        if (toSave.isNotEmpty()) growthInstanceRepository.saveAll(toSave)
    }

    // 알려진 한계 (code-review R9, accepted — prototype hardening backlog):
    //   요청-레벨 idempotency key 없음(전 money POST 공통). double-submit 은 SPARKLE 2배 소비+2배 진행 —
    //   사용자 자기-손해이지 farming/attacker-gain 아님. FE guard(buying ref) + debit 원자성으로 현실 위험 bound.
    //   서버측 dedup 은 cross-cutting 결정이라 백로그(exchange 와 동일 판단).

    /**
     * 반짝이 부스터: SPARKLE [SPARKLE_PER_BOOSTER] 소비 → 해당 종 +[BOOSTER_PROGRESS]회.
     * 잔액 부족 시 CurrencyService.debit 이 INSUFFICIENT_FUNDS. 개체 미생성 종은 생성.
     */
    @Transactional
    fun buyBooster(
        userId: String,
        speciesCode: String,
    ): GrowthItem {
        val species =
            growthSpeciesRepository.findById(speciesCode).orElseThrow {
                com.terraworld.common.exception
                    .BusinessException(com.terraworld.common.exception.ErrorCode.INVALID_INPUT, "존재하지 않는 종: $speciesCode")
            }
        currencyService.debit(userId, CurrencyCode.SPARKLE, SPARKLE_PER_BOOSTER, REASON_GROW_BOOST, REF_TYPE, speciesCode)

        val inst =
            growthInstanceRepository.findByUserIdAndSpeciesCode(userId, speciesCode)
                ?: GrowthInstance(userId = userId, speciesCode = speciesCode)
        inst.sparkleBoughtCount += BOOSTER_PROGRESS
        val stages = growthStageRepository.findAllByKindOrderByStageOrderAsc(species.kind)
        val goal = stages.maxOfOrNull { it.threshold } ?: 30
        val effectiveProgress = inst.naturalStreak + inst.sparkleBoughtCount
        inst.currentStage = stageForProgress(stages, effectiveProgress)
        growthInstanceRepository.save(inst)

        return GrowthItem(
            speciesCode = species.code,
            kind = GrowthItem.Kind.forValue(species.kind),
            nameKo = species.nameKo,
            currentStage = inst.currentStage,
            stageLabel = stages.firstOrNull { it.stageOrder == inst.currentStage }?.labelKo ?: "",
            effectiveProgress = effectiveProgress,
            goal = goal,
            dormant = false,
        )
    }

    /** effectiveProgress 기준 도달 최고 stage_order (threshold <= progress). 최소 1. */
    private fun stageForProgress(
        stages: List<GrowthStage>,
        progress: Int,
    ): Int = stages.filter { it.threshold <= progress }.maxOfOrNull { it.stageOrder } ?: 1
}
