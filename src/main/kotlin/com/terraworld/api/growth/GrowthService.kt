package com.terraworld.api.growth

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.api.grant.SpiritItems
import com.terraworld.api.notification.SpiritArrivedEvent
import com.terraworld.api.reward.RewardService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.growth.GrowthCycleState
import com.terraworld.domain.growth.GrowthInstance
import com.terraworld.domain.growth.GrowthInstanceRepository
import com.terraworld.domain.growth.GrowthSpecies
import com.terraworld.domain.growth.GrowthSpeciesRepository
import com.terraworld.domain.growth.GrowthStage
import com.terraworld.domain.growth.GrowthStageRepository
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.reward.AdRewardNonceInbox
import io.terraworld.api.model.GrowthItem
import io.terraworld.api.model.GrowthResponse
import io.terraworld.api.model.GrowthReviveRequest
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import io.terraworld.api.model.GrowthStage as ApiGrowthStage

/**
 * 육성(키우기) — 아프젝 v2 사이클 모델 (§R3).
 *
 * - 사이클: ACTIVE(진행) / LOST(기록 끊김) / COMPLETED(goal 달성). 판정은 조회·mutation 시 [settle] 로 lazy 수행 +
 *   스케줄러(KST 00:05, [GrowthResetScheduler]) 이중 경로 — 전이는 전부 CAS(bulk UPDATE) 라 동시 판정에도 1회만 적용.
 * - LOST: `DAYS.between(lastProgressDate, today) >= lost-after-days(2)` — lastProgressDate+1일의 KST 종료까지 미기록이면
 *   D+2 00:00 KST 부터. 판정 트리거는 일상 기록(advanceAllStreaks)만. LOST 에서는 기록이 진행을 올리지 않고(되살리기 필요),
 *   부스터 409 GROWTH_LOST. revive(루비/광고) 는 진행 유지 + ACTIVE 복귀, revive-dismiss 는 내일 새 사이클 리셋.
 * - COMPLETED: goal 도달 시 정령 아이템 지급(GrantService ITEM, items 가 소유권 SoT) + 당일 유지, 다음날 새 사이클 리셋
 *   (notifyNext 신청자에게 SPIRIT_ARRIVED 1회 — rows==1 을 받은 경로만 발행).
 */
@Service
class GrowthService(
    private val growthSpeciesRepository: GrowthSpeciesRepository,
    private val growthStageRepository: GrowthStageRepository,
    private val growthInstanceRepository: GrowthInstanceRepository,
    private val currencyService: CurrencyService,
    private val grantService: GrantService,
    private val rewardService: RewardService,
    private val recordRepository: RecordRepository,
    private val itemRepository: ItemRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: GrowthProperties = GrowthProperties(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val SPARKLE_PER_BOOSTER = 100L
        const val BOOSTER_PROGRESS = 10
        const val REASON_GROW_BOOST = "GROW_BOOST"
        const val REASON_GROW_REVIVE = "GROW_REVIVE"
        const val REASON_GROW_COMPLETE = "GROWTH_COMPLETE"
        const val REF_TYPE = "GROWTH"

        /** 개체 미생성 종의 cycleId 플레이스홀더 (첫 기록 전 — 안정값). */
        private const val NEW_CYCLE_SUFFIX = ":0"
    }

    // ─── 조회 ───────────────────────────────────────────────

    /** 전 종 상태. 조회 시 lazy 전이(LOST 판정 / 완료·보류 리셋)가 일어나므로 읽기 전용 tx 가 아니다. */
    @Transactional
    fun getGrowth(userId: String): GrowthResponse {
        val today = KstTime.today()
        val instances = growthInstanceRepository.findAllByUserId(userId).associateBy { it.speciesCode }
        val items =
            growthSpeciesRepository.findAllByOrderBySortOrderAsc().map { species ->
                val inst = instances[species.code]?.let { settle(it, species, today) }
                toItem(species, inst, today)
            }
        return GrowthResponse(items = items)
    }

    // ─── 일상 기록 hook ─────────────────────────────────────

    /**
     * 일상 기록 시 전 종 스탬프 진행 (하루 1회). 연속이면 streak++, 첫 기록(리셋 후 포함)이면 1.
     * LOST/COMPLETED 사이클은 진행하지 않는다 (LOST 는 되살리기, COMPLETED 는 당일 유지 후 리셋). 개체 미생성 종은 생성.
     * RecordService.createRecord hook — 습관 체크인은 호출하지 않는다.
     */
    @Transactional
    fun advanceAllStreaks(userId: String) {
        val today = KstTime.today()
        val now = KstTime.now() // 리뷰 S/CDX: 저장·비교 모두 KST (JVM UTC 혼용 방지)
        val existing = growthInstanceRepository.findAllByUserId(userId).associateBy { it.speciesCode }

        val toSave =
            growthSpeciesRepository.findAllByOrderBySortOrderAsc().mapNotNull { species ->
                val inst =
                    existing[species.code]?.let { settle(it, species, today) }
                        ?: GrowthInstance(userId = userId, speciesCode = species.code)
                if (inst.cycleState != GrowthCycleState.ACTIVE) return@mapNotNull null // LOST/COMPLETED — 진행 없음
                val lastDate = inst.lastProgressAt?.toLocalDate()
                if (lastDate == today) return@mapNotNull null // 오늘 이미 진행 — 멱등

                inst.naturalStreak =
                    if (lastDate == today.minusDays(1)) inst.naturalStreak + 1 else 1 // 연속 or 첫 스탬프
                inst.lastProgressAt = now
                applyProgress(inst, species, today, now)
                inst
            }
        if (toSave.isNotEmpty()) growthInstanceRepository.saveAll(toSave)
    }

    // ─── 부스터 ─────────────────────────────────────────────

    /**
     * 반짝이 부스터: SPARKLE [SPARKLE_PER_BOOSTER] 소비 → 해당 종 +[BOOSTER_PROGRESS]회. 잔액 부족 시 INSUFFICIENT_FUNDS.
     * LOST 사이클은 409 GROWTH_LOST (차감 전 거절), 완료된 사이클은 400.
     * 알려진 한계 (code-review R9, accepted): 요청-레벨 idempotency key 없음 — double-submit 은 사용자 자기-손해.
     */
    @Transactional
    fun buyBooster(
        userId: String,
        speciesCode: String,
    ): GrowthItem {
        val species = requireSpecies(speciesCode, ErrorCode.INVALID_INPUT)
        val today = KstTime.today()
        val inst =
            growthInstanceRepository.findByUserIdAndSpeciesCode(userId, speciesCode)?.let { settle(it, species, today) }
                ?: GrowthInstance(userId = userId, speciesCode = speciesCode)
        when (inst.cycleState) {
            GrowthCycleState.LOST -> throw BusinessException(ErrorCode.GROWTH_LOST)
            GrowthCycleState.COMPLETED -> throw BusinessException(ErrorCode.INVALID_INPUT, "이미 완료한 사이클이에요. 내일 새 사이클에서 사용해 주세요")
            GrowthCycleState.ACTIVE -> Unit
        }
        currencyService.debit(userId, CurrencyCode.SPARKLE, SPARKLE_PER_BOOSTER, REASON_GROW_BOOST, REF_TYPE, speciesCode)

        inst.sparkleBoughtCount += BOOSTER_PROGRESS
        applyProgress(inst, species, today, KstTime.now())
        val saved = growthInstanceRepository.save(inst)
        return toItem(species, saved, today)
    }

    // ─── 되살리기 / 보류 / 알림 신청 ─────────────────────────────

    /**
     * LOST 사이클 되살리기 — 진행(stampCount) 유지 + ACTIVE 복귀. RUBY 는 reviveRubyCost 차감, AD 는 광고 보상과
     * 동일한 단계 정책으로 nonce 를 소비한다(authoritative 에서는 SSV 검증 완료 SERVER nonce 만 허용).
     * revive-dismiss 로 보류된 당일(reviveSnoozedUntil > today)은 409 GROWTH_REVIVE_SNOOZED.
     * 오늘 이미 일상 기록이 있으면 그 기록을 스탬프로 반영(streak+1), 없으면 오늘 기록이 이어지도록 기준일을 어제로 둔다.
     */
    @Transactional
    fun revive(
        userId: String,
        speciesCode: String,
        method: GrowthReviveRequest.Method,
        adNonce: String?,
    ): GrowthItem {
        val species = requireSpecies(speciesCode, ErrorCode.GROWTH_SPECIES_NOT_FOUND)
        val today = KstTime.today()
        val inst =
            growthInstanceRepository.findByUserIdAndSpeciesCode(userId, speciesCode)?.let { settle(it, species, today) }
                ?: return toItem(species, null, today)
        if (inst.cycleState != GrowthCycleState.LOST) return toItem(species, inst, today)
        val snoozedUntil = inst.reviveSnoozedUntilKstDate
        if (snoozedUntil != null && snoozedUntil.isAfter(today)) throw BusinessException(ErrorCode.GROWTH_REVIVE_SNOOZED)

        when (method) {
            GrowthReviveRequest.Method.RUBY ->
                if (properties.reviveRubyCost > 0) {
                    currencyService.debit(userId, CurrencyCode.RUBY, properties.reviveRubyCost, REASON_GROW_REVIVE, REF_TYPE, speciesCode)
                }
            GrowthReviveRequest.Method.AD ->
                // 광고 보상과 같은 gate 를 사용해 authoritative 전환 시 client 생성 nonce 를 함께 차단한다.
                rewardService.consumeAdNonce(userId, adNonce, AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE)
        }

        val now = KstTime.now()
        inst.cycleState = GrowthCycleState.ACTIVE
        inst.lostAt = null
        inst.reviveSnoozedUntilKstDate = null
        inst.dormancyRecoveredAt = now
        if (recordRepository.findMaxRecordedDate(userId) == today) {
            // 오늘 기록이 이미 있음 — 되살린 사이클에 오늘 스탬프 반영
            inst.naturalStreak += 1
            inst.lastProgressAt = now
        } else {
            // 오늘 기록이 오면 연속으로 이어지도록 기준일을 어제로
            inst.lastProgressAt = today.minusDays(1).atStartOfDay()
        }
        applyProgress(inst, species, today, now)
        val saved = growthInstanceRepository.save(inst)
        log.info("growth.revive user={} species={} method={} progress={}", userId, speciesCode, method, saved.effectiveProgress)
        return toItem(species, saved, today)
    }

    /** 되살리기 팝업 닫기 — reviveSnoozedUntil = 오늘+1 (당일 재시도 불가, 다음날 새 사이클 리셋). LOST 가 아니면 변경 없음. */
    @Transactional
    fun dismissRevive(
        userId: String,
        speciesCode: String,
    ): GrowthItem {
        val species = requireSpecies(speciesCode, ErrorCode.GROWTH_SPECIES_NOT_FOUND)
        val today = KstTime.today()
        val inst =
            growthInstanceRepository.findByUserIdAndSpeciesCode(userId, speciesCode)?.let { settle(it, species, today) }
                ?: return toItem(species, null, today)
        if (inst.cycleState != GrowthCycleState.LOST) return toItem(species, inst, today)
        inst.reviveSnoozedUntilKstDate = today.plusDays(1)
        return toItem(species, growthInstanceRepository.save(inst), today)
    }

    /** 다음 정령 도착 알림(SPIRIT_ARRIVED) 신청 토글. 개체 미생성 종은 생성해 플래그를 보존한다. */
    @Transactional
    fun toggleNotifyNext(
        userId: String,
        speciesCode: String,
    ): GrowthItem {
        val species = requireSpecies(speciesCode, ErrorCode.GROWTH_SPECIES_NOT_FOUND)
        val today = KstTime.today()
        val inst =
            growthInstanceRepository.findByUserIdAndSpeciesCode(userId, speciesCode)?.let { settle(it, species, today) }
                ?: GrowthInstance(userId = userId, speciesCode = speciesCode)
        inst.notifyNext = !inst.notifyNext
        return toItem(species, growthInstanceRepository.save(inst), today)
    }

    // ─── 스케줄러 경로 ──────────────────────────────────────────

    /**
     * 개체 1건의 기한 도래 리셋 (스케줄러 KST 00:05 경로). 조회 경로의 [settle] 과 같은 CAS 를 쓰므로 어느 쪽이 먼저 와도 1회만 전환.
     * @return 전환 여부
     */
    @Transactional
    fun resetDueById(
        instanceId: Long,
        today: LocalDate,
    ): Boolean {
        val inst = growthInstanceRepository.findById(instanceId).orElse(null) ?: return false
        val species = growthSpeciesRepository.findById(inst.speciesCode).orElse(null) ?: return false
        val before = inst.cycleId
        val after = settle(inst, species, today)
        return after.cycleId != before
    }

    // ─── 판정/전이 ─────────────────────────────────────────────

    /**
     * 조회·mutation 공통 lazy 전이. 순서: (1) 완료 리셋 기한 도래 → 새 사이클(+SPIRIT_ARRIVED) (2) 되살리기 보류 만료 → 새 사이클
     * (3) ACTIVE 인데 기록 끊김 → LOST. 전이는 CAS 라 동시 호출에도 1회. 반환값은 전이 반영 인스턴스(재조회).
     */
    private fun settle(
        inst: GrowthInstance,
        species: GrowthSpecies,
        today: LocalDate,
    ): GrowthInstance {
        val now = KstTime.now()
        var current = inst
        val resetDue = current.resetDueKstDate?.let { !it.isAfter(today) } == true
        if (current.cycleState == GrowthCycleState.COMPLETED && current.resetProcessedAt == null && resetDue) {
            val notify = current.notifyNext
            val rows = growthInstanceRepository.resetCompletedCycle(current.id, today, now, UUID.randomUUID())
            current = reload(current)
            if (rows == 1) {
                log.info("growth.reset.completed user={} species={} notify={}", current.userId, species.code, notify)
                if (notify) eventPublisher.publishEvent(SpiritArrivedEvent(userId = current.userId, speciesNameKo = species.nameKo))
            }
        }
        if (current.cycleState == GrowthCycleState.LOST && current.reviveSnoozedUntilKstDate?.let { !it.isAfter(today) } == true) {
            val rows = growthInstanceRepository.resetSnoozedCycle(current.id, today, now, UUID.randomUUID())
            current = reload(current)
            if (rows == 1) log.info("growth.reset.snoozed user={} species={}", current.userId, species.code)
        }
        val observedLastProgressAt = current.lastProgressAt
        if (current.cycleState == GrowthCycleState.ACTIVE && observedLastProgressAt != null && isLost(observedLastProgressAt, today)) {
            // 관측한 last_progress_at 을 CAS 조건에 포함 — read 이후 커밋된 기록(D+1 23:59)이 있으면 rows==0 이고,
            // 재조회 결과(갱신된 last_progress_at, ACTIVE 유지)를 그대로 쓴다. 덮어쓰기 없음.
            val rows = growthInstanceRepository.markLost(current.id, now, observedLastProgressAt)
            current = reload(current)
            if (rows == 0) log.debug("growth.lost.skipped id={} — 판정 read 이후 진행 갱신(stale read), ACTIVE 유지", current.id)
        }
        return current
    }

    /** CAS(bulk UPDATE, clearAutomatically) 뒤 재조회 — 영속성 컨텍스트가 비워져 기존 인스턴스는 detached. */
    private fun reload(inst: GrowthInstance): GrowthInstance = growthInstanceRepository.findById(inst.id).orElse(inst)

    /**
     * LOST 판정 — 마지막 진행일 + lost-after-days 가 오늘 이하. 기준: `DAYS.between(lastProgressDate, today) >= lostAfterDays`.
     * lastProgressAt 없으면(신규/리셋 직후) LOST 아님.
     */
    internal fun isLost(
        lastProgressAt: LocalDateTime?,
        today: LocalDate,
    ): Boolean {
        val last = lastProgressAt ?: return false
        return ChronoUnit.DAYS.between(last.toLocalDate(), today) >= properties.lostAfterDays
    }

    /** 진행 반영 후 단계 재계산 + goal 도달 시 완료 처리(정령 지급, 리셋 기한 설정). */
    private fun applyProgress(
        inst: GrowthInstance,
        species: GrowthSpecies,
        today: LocalDate,
        now: LocalDateTime,
    ) {
        val stages = stagesOf(species)
        inst.currentStage = stageForProgress(stages, inst.effectiveProgress)
        if (inst.cycleState == GrowthCycleState.ACTIVE && inst.effectiveProgress >= properties.goal) {
            inst.cycleState = GrowthCycleState.COMPLETED
            inst.completedKstDate = today
            inst.completedAt = now
            inst.resetDueKstDate = today.plusDays(1)
            inst.resetProcessedAt = null
            grantSpiritItem(inst, species)
        }
    }

    /** goal 달성 정령 아이템 지급 — items 가 소유권 SoT. 사이클당 1회(키 growth:{cycleId}:spirit), 이미 보유면 no-op. */
    private fun grantSpiritItem(
        inst: GrowthInstance,
        species: GrowthSpecies,
    ) {
        if (species.kind != "SPIRIT") return
        val slug = SpiritItems.slugForCharacter(species.code)
        if (itemRepository.findBySlug(slug).isEmpty) {
            // 시드 누락 — 기록 tx 를 깨지 않고 경고만 (운영 대시보드 감지용)
            log.warn("growth.complete.spirit-item-missing slug={} user={} species={}", slug, inst.userId, species.code)
            return
        }
        grantService.grant(inst.userId, GrantType.ITEM, slug, 1, "growth:${inst.cycleId}:spirit", REASON_GROW_COMPLETE)
    }

    private fun requireSpecies(
        speciesCode: String,
        notFoundCode: ErrorCode,
    ): GrowthSpecies =
        growthSpeciesRepository.findById(speciesCode).orElseThrow {
            BusinessException(notFoundCode, "존재하지 않는 종: $speciesCode")
        }

    private fun stagesOf(species: GrowthSpecies): List<GrowthStage> = growthStageRepository.findAllByKindOrderByStageOrderAsc(species.kind)

    /** effectiveProgress 기준 도달 최고 stage_order (threshold <= progress). 최소 1. */
    private fun stageForProgress(
        stages: List<GrowthStage>,
        progress: Int,
    ): Int = stages.filter { it.threshold <= progress }.maxOfOrNull { it.stageOrder } ?: 1

    private fun toItem(
        species: GrowthSpecies,
        inst: GrowthInstance?,
        today: LocalDate,
    ): GrowthItem {
        val stages = stagesOf(species)
        val progress = inst?.effectiveProgress ?: 0
        val currentStage = stageForProgress(stages, progress)
        val state = inst?.cycleState ?: GrowthCycleState.ACTIVE
        return GrowthItem(
            speciesCode = species.code,
            kind = GrowthItem.Kind.forValue(species.kind),
            nameKo = species.nameKo,
            currentStage = currentStage,
            stageLabel = stages.firstOrNull { it.stageOrder == currentStage }?.labelKo ?: "",
            effectiveProgress = progress,
            stampCount = progress,
            goal = properties.goal,
            dormant = state == GrowthCycleState.LOST,
            cycleId = inst?.cycleId?.toString() ?: (species.code + NEW_CYCLE_SUFFIX),
            cycleState = GrowthItem.CycleState.forValue(state.name),
            stages = stages.map { ApiGrowthStage(stage = it.stageOrder, threshold = it.threshold, label = it.labelKo) },
            reviveRubyCost = properties.reviveRubyCost.toInt(),
            completedToday = inst?.completedKstDate == today,
            notifyNext = inst?.notifyNext ?: false,
            lostAt = inst?.lostAt?.atOffset(ZoneOffset.UTC),
            completedAt = inst?.completedAt?.atOffset(ZoneOffset.UTC),
            reviveSnoozedUntil = inst?.reviveSnoozedUntilKstDate,
        )
    }
}
