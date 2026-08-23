package com.terraworld.api.terrarium

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
import com.terraworld.api.grant.SpiritItems
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.currency.CurrencyCode
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.terrarium.TierConfigRepository
import io.terraworld.api.model.TierCatalogResponse
import io.terraworld.api.model.TierInfo
import io.terraworld.api.model.TierUnlockResponse
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime

/**
 * 테라리움 티어 (낙서장 P2 → 아프젝 v2 §R1) — 루비 전용 순차 해금 + 표시 중 병(activeTier) 분리.
 *
 * - `terrariums.tier` = 해금 최고 티어(highestUnlockedTier), `terrariums.active_tier` = 표시 중 병.
 * - unlockTier 원자 순서: terrarium 조회 → 순차(target==highest+1, 활성 티어) 검증 → RUBY 차감(rubyCost 만) →
 *   조건부 CAS 티어 상승(casTier, WHERE tier=current) → 티어 정령(GrantService — items SoT ITEM + 레거시 SPIRIT) → 응답.
 *   CAS rows==0(동시 요청 선점) 은 CONCURRENT_MODIFICATION. 해금해도 active_tier 는 **변경하지 않는다**.
 * - 카탈로그는 활성 티어만(HOUSE_TANK 비활성 제외). unlocked = tierOrder <= highest order, active = tier == activeTier.
 */
@Service
class TierService(
    private val terrariumRepository: TerrariumRepository,
    private val tierConfigRepository: TierConfigRepository,
    private val currencyService: CurrencyService,
    private val grantService: GrantService,
) {
    companion object {
        const val REASON_TIER_UNLOCK = "TIER_UNLOCK"
        const val REF_TYPE = "TIER"
    }

    @Transactional(readOnly = true)
    fun getCatalog(userId: String): TierCatalogResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        val configs = tierConfigRepository.findAllByIsActiveTrueOrderByTierOrderAsc()
        // 리뷰 Q#12: unknown tier 는 silent fallback(?:1) 대신 fail-fast (unlockTier 와 일관 — 데이터 오염 은폐 방지).
        // 비활성 티어(HOUSE_TANK)에 이미 도달한 계정은 전체 카탈로그에서 order 를 읽는다.
        val highestOrder = orderOf(terrarium.tier, configs)
        return TierCatalogResponse(
            currentTier = terrarium.tier,
            activeTier = terrarium.activeTier,
            highestUnlockedTier = terrarium.tier,
            tiers =
                configs.map {
                    TierInfo(
                        tier = it.tier,
                        tierOrder = it.tierOrder,
                        level = it.level,
                        nameKo = it.nameKo,
                        descriptionKo = it.descriptionKo,
                        sparkleCost = it.sparkleCost,
                        rubyCost = it.rubyCost,
                        slots = it.slots,
                        spiritCode = it.spiritCode,
                        unlocked = it.tierOrder <= highestOrder,
                        active = it.tier == terrarium.activeTier,
                        previewAssetUrl = it.previewAssetUrl,
                    )
                },
        )
    }

    private fun orderOf(
        tier: String,
        activeConfigs: List<com.terraworld.domain.terrarium.TierConfig>,
    ): Int =
        activeConfigs.firstOrNull { it.tier == tier }?.tierOrder
            ?: tierConfigRepository.findById(tier).map { it.tierOrder }.orElse(null)
            ?: throw BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 티어: $tier")

    @Transactional
    fun unlockTier(
        userId: String,
        targetTier: String,
    ): TierUnlockResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        val target =
            tierConfigRepository.findById(targetTier).orElseThrow { BusinessException(ErrorCode.INVALID_INPUT) }
        val current =
            tierConfigRepository.findById(terrarium.tier).orElseThrow { BusinessException(ErrorCode.INVALID_INPUT) }

        // 비활성 티어(HOUSE_TANK)는 해금 불가
        if (!target.isActive) throw BusinessException(ErrorCode.INVALID_INPUT)
        // 순차 해금만 (2·3 없이 4 불가)
        if (target.tierOrder != current.tierOrder + 1) throw BusinessException(ErrorCode.INVALID_INPUT)

        // 아프젝 v2: 비용은 루비 전용 — rubyCost 만 차감 (sparkleCost 는 시드 0, 하위호환 필드). 잔액 부족 시 tx 롤백.
        if (target.rubyCost > 0) {
            currencyService.debit(userId, CurrencyCode.RUBY, target.rubyCost, REASON_TIER_UNLOCK, REF_TYPE, targetTier)
        }

        // M5: 조건부 원자 상승. read→검증→set(무락) 은 동시 요청 시 double debit(사용자 손해) 창이 있으므로,
        // WHERE tier=current CAS 로 관측한 상태에서만 상승. rows==0 = 동시 요청이 먼저 상승 → tx 롤백으로 debit 원복.
        // active_tier 는 건드리지 않는다 (현재 병 유지 — 계약 §R1).
        val casRows = terrariumRepository.casTier(terrarium.id, current.tier, targetTier, LocalDateTime.now())
        if (casRows == 0) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)

        // 티어 보상 정령 — items 가 소유권 SoT(ITEM executor, `{code}-spirit`). user_characters(SPIRIT)도 레거시 표면 호환으로 함께 멱등 지급.
        var grantedSpirit: String? = null
        val spirit = target.spiritCode
        if (spirit != null) {
            val granted =
                grantService.grant(userId, GrantType.ITEM, SpiritItems.slugForCharacter(spirit), 1, "tier-$targetTier:item", REASON_TIER_UNLOCK)
            grantService.grant(userId, GrantType.SPIRIT, spirit, 1, "tier-$targetTier", REASON_TIER_UNLOCK)
            if (granted) grantedSpirit = spirit
        }

        return TierUnlockResponse(
            tier = targetTier,
            slots = target.slots,
            grantedSpirit = grantedSpirit,
            updatedCurrency = currencyService.currencyResponse(userId),
        )
    }
}
