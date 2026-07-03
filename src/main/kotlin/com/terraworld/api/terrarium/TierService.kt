package com.terraworld.api.terrarium

import com.terraworld.api.currency.CurrencyService
import com.terraworld.api.grant.GrantService
import com.terraworld.api.grant.GrantType
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
 * 테라리움 티어 (낙서장 P2) — 화폐-게이트 순차 해금. evolution(레벨-게이트) 대체.
 * unlockTier 원자 순서: terrarium 조회 → 순차(target==current+1) 검증 → SPARKLE/RUBY 차감
 * (CurrencyService @Version + ledger) → 조건부 CAS 티어 상승(casTier, WHERE tier=current) → 티어 정령
 * (GrantService.SPIRIT 멱등) → 응답. CAS rows==0(동시 요청 선점/이미 상승) 은 CONCURRENT_MODIFICATION.
 * 잔액 mutation 실패(INSUFFICIENT_FUNDS) 및 CAS 실패는 tx 롤백으로 원자성 보장(double debit 방지).
 * 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md
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
        val configs = tierConfigRepository.findAllByOrderByTierOrderAsc()
        // 리뷰 Q#12: unknown tier 는 silent fallback(?:1) 대신 fail-fast (unlockTier 와 일관 — 데이터 오염 은폐 방지)
        val currentOrder =
            configs.firstOrNull { it.tier == terrarium.tier }?.tierOrder
                ?: throw BusinessException(ErrorCode.INVALID_INPUT, "알 수 없는 티어: ${terrarium.tier}")
        return TierCatalogResponse(
            currentTier = terrarium.tier,
            tiers =
                configs.map {
                    TierInfo(
                        tier = it.tier,
                        tierOrder = it.tierOrder,
                        nameKo = it.nameKo,
                        sparkleCost = it.sparkleCost,
                        rubyCost = it.rubyCost,
                        slots = it.slots,
                        spiritCode = it.spiritCode,
                        unlocked = it.tierOrder <= currentOrder,
                    )
                },
        )
    }

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

        // 순차 해금만 (2·3 없이 4 불가)
        if (target.tierOrder != current.tierOrder + 1) throw BusinessException(ErrorCode.INVALID_INPUT)

        // 비용 차감 (SPARKLE → RUBY 순; 잔액 부족 시 tx 롤백으로 원자성)
        if (target.sparkleCost > 0) {
            currencyService.debit(userId, CurrencyCode.SPARKLE, target.sparkleCost, REASON_TIER_UNLOCK, REF_TYPE, targetTier)
        }
        if (target.rubyCost > 0) {
            currencyService.debit(userId, CurrencyCode.RUBY, target.rubyCost, REASON_TIER_UNLOCK, REF_TYPE, targetTier)
        }

        // M5: 조건부 원자 상승. read→검증→set(무락) 은 동시 요청 시 double debit(사용자 손해) 창이 있으므로,
        // WHERE tier=current CAS 로 관측한 상태에서만 상승. rows==0 = 동시 요청이 먼저 상승 → tx 롤백으로 debit 원복.
        val casRows = terrariumRepository.casTier(terrarium.id, current.tier, targetTier, LocalDateTime.now())
        if (casRows == 0) throw BusinessException(ErrorCode.CONCURRENT_MODIFICATION)

        // 티어 보상 정령 (user_characters 멱등 지급)
        var grantedSpirit: String? = null
        val spirit = target.spiritCode
        if (spirit != null) {
            val granted =
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
