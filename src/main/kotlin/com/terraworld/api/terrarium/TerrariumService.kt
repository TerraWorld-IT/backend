package com.terraworld.api.terrarium

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.item.ItemLayout
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumPlacement
import com.terraworld.domain.terrarium.TerrariumPlacementHistory
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.terrarium.TerrariumPlacementRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.BackgroundInfo
import io.terraworld.api.model.HeartResponse
import io.terraworld.api.model.PlacedItemDetail
import io.terraworld.api.model.PlacementRequest
import io.terraworld.api.model.TerrariumResponse
import io.terraworld.api.model.WiltingState
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import io.terraworld.api.model.FreePlacementItem as ApiFreePlacementItem

/** 슬롯 재저장(delete-recreate) 시 보존할 자유배치 편집 상태 6필드. */
private data class FreeEditState(
    val freeXPixel: Int?,
    val freeYPixel: Int?,
    val isFreePlacement: Boolean,
    val scale: Float,
    val zIndex: Int,
    val flipped: Boolean,
)

/**
 * ARCH-008-phase-12: 시그니처 + 반환 모두 generated DTO 사용.
 *
 * service 가 enum 변환 책임 흡수:
 *  - domain `ItemLayout` → generated `PlacedItemDetail.ItemLayout` (forValue, hardcoded 안전)
 *
 * 낙서장 P1/P2: 레벨/EXP 제거 + evolution → tier(화폐-게이트). maxSlots 는 tier_configs.slots 기준.
 */
@Service
class TerrariumService(
    private val terrariumRepository: TerrariumRepository,
    // 낙서장 P1 cutover(dual-write): 하트 보상 = 코인(COIN) 정규화 잔액에도 반영
    private val currencyService: com.terraworld.api.currency.CurrencyService,
    private val terrariumPlacementRepository: TerrariumPlacementRepository,
    private val userRepository: UserRepository,
    private val userItemRepository: UserItemRepository,
    private val itemRepository: ItemRepository,
    // apjek social loop: 배경 변경(setBackground) — 아이템 파생 배경 행 find-or-create
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository,
    // 낙서장 P2: maxSlots = 현재 티어 슬롯 (구 level_configs 대체)
    private val tierConfigRepository: com.terraworld.domain.terrarium.TierConfigRepository,
    private val recordRepository: RecordRepository,
    private val placementHistoryRepository: TerrariumPlacementHistoryRepository,
    private val entitlementService: com.terraworld.api.entitlement.EntitlementService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        // 시들기 단계 임계치 (마지막 기록 이후 경과 일수 기준)
        private const val WILT_STAGE_1_DAYS = 3L
        private const val WILT_STAGE_2_DAYS = 7L
        private const val WILT_STAGE_3_DAYS = 14L
    }

    @Transactional(readOnly = true)
    fun getTerrarium(userId: String): TerrariumResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }
        return buildTerrariumResponse(userId, terrarium)
    }

    /**
     * BE-03 (2026-07-15 성능 감사): 이미 로드한 terrarium 으로 응답 조립.
     *
     * - placedItems: 컬렉션 lazy 순회(placement 마다 item 프록시 SELECT — N+1) 대신
     *   [TerrariumPlacementRepository.findAllByTerrariumUserIdOrderById] 의 @EntityGraph(item)
     *   fetch join 1쿼리 (free.vue 로드 경로와 동일 패턴). OrderById 라 응답 순서 결정적.
     * - wilting: computeWiltingState 가 같은 terrarium 을 findByUserId 로 재조회하던
     *   중복 제거 — 로드된 terrarium 을 인자로 전달.
     * - updatePlacements 의 응답 재조회도 본 메서드 재사용 — terrarium 재-findByUserId 없이
     *   placements 1쿼리만 (JPQL 실행 전 auto-flush 로 재INSERT 분 반영 보장).
     */
    private fun buildTerrariumResponse(
        userId: String,
        terrarium: com.terraworld.domain.terrarium.Terrarium,
    ): TerrariumResponse {
        // 낙서장 P2: 배치 슬롯 = 현재 티어 슬롯 (구 level_configs 대체)
        // BE-08: findSlotsByTier 는 Caffeine TTL 10분 캐시 (4행 seed config — 매 조회 SELECT 제거)
        val maxSlots = tierConfigRepository.findSlotsByTier(terrarium.tier) ?: 6

        // 기존 fetch join 1쿼리를 placedItems / freePlacements 양쪽 조립에 공유 (추가 쿼리 없음).
        val placements = terrariumPlacementRepository.findAllByTerrariumUserIdOrderById(userId)

        return TerrariumResponse(
            terrariumId = terrarium.id,
            background =
                BackgroundInfo(
                    id = terrarium.background.id,
                    name = terrarium.background.name,
                    assetUrl = terrarium.background.assetUrl,
                ),
            placedItems =
                placements.map { p ->
                    PlacedItemDetail(
                        id = p.id,
                        itemId = p.item.id,
                        itemImage = p.item.assetUrl,
                        itemName = p.item.name,
                        itemLayout = PlacedItemDetail.ItemLayout.forValue(p.item.layout.name),
                        isAnimated = p.item.isAnimated,
                        itemSlug = p.item.slug,
                        slotId = p.slotId,
                    )
                },
            maxSlots = maxSlots,
            tier = terrarium.tier,
            wilting = computeWiltingState(userId, terrarium),
            // apjek social loop: 자유배치 사용 중(활성 항목 존재)일 때만 전체 배치의 실좌표 목록을 동봉 —
            // 미사용이면 생략(null, spec 계약). 항목 구성은 GET /terrarium/free-placement (PlacementService.
            // listForUser) 와 동일 — 친구 방문 화면(FE TerrariumView)이 홈과 같은 해석으로 재현한다
            // (비자유배치 항목은 FE 가 index 폴백 좌표 사용).
            freePlacements =
                if (placements.any { it.isFreePlacement }) {
                    placements.map { p ->
                        ApiFreePlacementItem(
                            placementId = p.id,
                            itemId = p.item.id,
                            itemName = p.item.name,
                            itemImage = p.item.assetUrl,
                            itemLayout = p.item.layout.name,
                            posX = PlacementService.storeToRatio(p.freeXPixel),
                            posY = PlacementService.storeToRatio(p.freeYPixel),
                            isFreePlacement = p.isFreePlacement,
                            scale = p.scale,
                            flipped = p.flipped,
                            zIndex = p.zIndex,
                        )
                    }
                } else {
                    null
                },
        )
    }

    /**
     * 테라리움 배경 변경 (PUT /terrarium/background, apjek social loop).
     *
     * 검증 순서는 spec(terrarium-background.yaml) 계약: 미존재/미보유 → 404 (존재 숨김 —
     * 두 경우 모두 동일 ITEM_NOT_FOUND 응답으로 존재 여부를 드러내지 않는다), 보유했지만
     * layout != BACKGROUND → 409 NOT_BACKGROUND_LAYOUT.
     *
     * terrariums.background_id 는 terrarium_backgrounds FK (substrate 재사용 — 스키마 변경 없음).
     * 아이템에서 파생한 배경 행을 asset_url 기준 find-or-create 로 확보해 연결한다.
     * 유니크 제약이 없어 동시 최초 설정 시 중복 행이 생길 수 있으나 둘 다 유효한 FK 대상이라 무해
     * (조회는 최소 id 선택으로 결정적).
     */
    @Transactional
    fun setBackground(
        userId: String,
        itemId: Long,
    ): TerrariumResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }

        val item =
            itemRepository
                .findById(itemId)
                .orElseThrow { BusinessException(ErrorCode.ITEM_NOT_FOUND) }
        if (!userItemRepository.existsByUserIdAndItemId(userId, itemId)) {
            // 존재 숨김: 미존재와 동일한 응답 (updatePlacements 의 보유 메시지 관례와 달리 메시지도 비노출)
            throw BusinessException(ErrorCode.ITEM_NOT_FOUND)
        }
        if (item.layout != ItemLayout.BACKGROUND) {
            throw BusinessException(ErrorCode.NOT_BACKGROUND_LAYOUT)
        }

        val background =
            terrariumBackgroundRepository.findFirstByAssetUrlOrderByIdAsc(item.assetUrl)
                ?: terrariumBackgroundRepository.save(
                    TerrariumBackground(
                        name = item.name,
                        assetUrl = item.assetUrl,
                        // 아이템 파생 배경 표식 — seed 카탈로그(DEFAULT/LEVEL)와 구분
                        unlockCondition = "ITEM",
                        unlockValue = 0,
                    ),
                )
        terrarium.background = background
        terrarium.updatedAt = LocalDateTime.now()
        terrariumRepository.save(terrarium)

        return buildTerrariumResponse(userId, terrarium)
    }

    /**
     * P-WILT-001 + J-WILT-001 (UltraPlan v3, 2026-05-18):
     *
     * 시들기 단계는 max(마지막 record date, terrarium.wiltRecoveredAt) 기준의
     * 경과 일수로 계산. RewardService.claimAdReward 가 wiltRecoveredAt 을 now() 로
     * 갱신하면 본 함수가 stage 0 으로 자동 reset (Codex pre-audit J-WILT 해결).
     *
     * 둘 다 null = 기록 없는 신규 사용자 → stage 0 / daysSinceRecord null.
     *
     * BE-03: 호출자가 이미 로드한 terrarium 을 인자로 받는다 — 기존에는 같은 terrarium 을
     * findByUserId 로 다시 SELECT 했음 (getTerrarium 경로에서 호출당 1쿼리 중복).
     */
    private fun computeWiltingState(
        userId: String,
        terrarium: com.terraworld.domain.terrarium.Terrarium,
    ): WiltingState {
        val lastRecordDate = recordRepository.findMaxRecordedDate(userId)
        val wiltRecoveredDate = terrarium.wiltRecoveredAt?.toLocalDate()

        val effective =
            listOfNotNull(lastRecordDate, wiltRecoveredDate).maxOrNull()
                ?: return WiltingState(stage = 0, daysSinceRecord = null)

        val days = ChronoUnit.DAYS.between(effective, KstTime.today())
        val stage =
            when {
                days >= WILT_STAGE_3_DAYS -> 3
                days >= WILT_STAGE_2_DAYS -> 2
                days >= WILT_STAGE_1_DAYS -> 1
                else -> 0
            }
        return WiltingState(stage = stage, daysSinceRecord = days.toInt())
    }

    @Transactional
    fun updatePlacements(
        userId: String,
        request: PlacementRequest,
    ): TerrariumResponse {
        val terrarium =
            terrariumRepository
                .findByUserId(userId)
                .orElseThrow { BusinessException(ErrorCode.TERRARIUM_NOT_FOUND) }

        // 낙서장 P2 (FP-05): 배치 슬롯 수 = 현재 tier 슬롯(GLASS_JAR 6 / LARGE_JAR 10 / GRAND_TANK 16 / HOUSE_TANK 24).
        // R4-TIER (2026-07-15 적대적 리뷰): 쓰기 경로 검증은 캐시(findSlotsByTier, TTL 10분)
        // 우회 — admin 이 슬롯을 축소한 직후 stale 캐시 값으로 초과 배치가 영속되는 것을 차단.
        // findById 직접 조회(fresh). 읽기 경로(getTerrarium/buildTerrariumResponse)는 캐시 유지.
        val maxSlots = tierConfigRepository.findById(terrarium.tier).map { it.slots }.orElse(6)

        // BE-03 (2026-07-15 성능 감사): 검증 루프의 아이템당 findById + existsByUserIdAndItemId
        // (배치 N개당 2N 쿼리) → 루프 전 일괄 로드 2쿼리. 검증 순서/예외 시맨틱은 기존과 동일
        // (placement 순서대로 미존재 → ITEM_NOT_FOUND, 미보유 → ITEM_NOT_FOUND+메시지, 슬롯 범위 → INVALID_SLOT).
        val requestedItemIds = request.placedItems.map { it.itemId }.toSet()
        val itemsById: Map<Long, com.terraworld.domain.item.Item> =
            if (requestedItemIds.isEmpty()) {
                emptyMap()
            } else {
                itemRepository.findAllById(requestedItemIds).associateBy { it.id }
            }
        val ownedItemIds: Set<Long> =
            if (requestedItemIds.isEmpty()) {
                emptySet()
            } else {
                userItemRepository.findOwnedItemIds(userId, requestedItemIds)
            }

        for (placement in request.placedItems) {
            val item = itemsById[placement.itemId] ?: throw BusinessException(ErrorCode.ITEM_NOT_FOUND)

            if (item.id !in ownedItemIds) {
                throw BusinessException(ErrorCode.ITEM_NOT_FOUND, "보유하지 않은 아이템입니다")
            }

            // 낙서장 자유배치: slotId 는 배치 인덱스(0..maxSlots-1). 시각 위치는 free placement(posX/posY)가
            // 결정하므로 구 5-slot grid 모델의 slotId→layout 제약(0,1=BG/2,4=FG/3=FIG)은 폐기.
            // tier 가 부여한 슬롯 수만큼 배치를 허용한다. (구 제약은 tier≥LARGE_JAR 의 6~번 슬롯 배치를 막았음.)
            if (placement.slotId < 0 || placement.slotId >= maxSlots) {
                throw BusinessException(ErrorCode.INVALID_SLOT)
            }
        }

        val existingItemIds = terrarium.placedItems.map { it.item.id }.toSet()
        // J-FREE-001 (2026-06-02): 슬롯 재배치는 delete-recreate 이므로, 그대로 두면
        // PlacementService 가 저장한 자유배치 좌표(free_x/y_pixel + is_free_placement)가
        // 함께 wiped 된다. 재배치되어 살아남는 placement 의 free 좌표를 (itemId, slotId) 기준으로
        // 보존 후 복원. (Codex audit LOW: itemId 단독 키는 같은 아이템이 두 슬롯에 있을 때
        // last-write-wins 로 충돌 — slotId 까지 포함해 placement 단위로 매칭. 슬롯이 바뀌면
        // free 좌표는 의도적으로 리셋.)
        // 슬롯 재저장 시 자유배치 편집 상태(위치 + scale/zIndex/flipped)를 전부 보존한다.
        // 일부만 보존하면 slot 재저장 후 크기/깊이/반전이 기본값으로 silently 리셋됨 (review MEDIUM).
        val freeBySlot =
            terrarium.placedItems
                .filter { it.isFreePlacement || it.freeXPixel != null }
                .associate {
                    (it.item.id to it.slotId) to
                        FreeEditState(it.freeXPixel, it.freeYPixel, it.isFreePlacement, it.scale, it.zIndex, it.flipped)
                }

        terrariumPlacementRepository.deleteAllByTerrariumId(terrarium.id)
        terrarium.placedItems.clear()
        // 사전존재 버그 (2026-06-02 e2e 발견): delete(deferred) + 재INSERT 를 한 트랜잭션에서 하면
        // Hibernate action queue 가 INSERT 를 DELETE 보다 먼저 실행 → 같은 slot_id 재배치 시
        // ux_terrarium_items_terrarium_slot 유니크 위반 (TERRARIUM_SLOT_CONFLICT 409). 슬롯 재저장이
        // 항상 실패하던 문제. flush() 로 DELETE 를 재INSERT 전에 강제 실행.
        terrariumPlacementRepository.flush()

        request.placedItems.forEach { p ->
            // BE-03: 재삽입 루프의 중복 findById 제거 — 위 일괄 로드 맵 재사용 (검증 통과 = 존재 보장).
            val item = itemsById.getValue(p.itemId)
            val free = freeBySlot[item.id to p.slotId]
            terrarium.placedItems.add(
                TerrariumPlacement(
                    terrarium = terrarium,
                    item = item,
                    slotId = p.slotId,
                    freeXPixel = free?.freeXPixel,
                    freeYPixel = free?.freeYPixel,
                    isFreePlacement = free?.isFreePlacement ?: false,
                    scale = free?.scale ?: 1f,
                    zIndex = free?.zIndex ?: 0,
                    flipped = free?.flipped ?: false,
                ),
            )
            if (item.id !in existingItemIds) {
                placementHistoryRepository.save(
                    TerrariumPlacementHistory(
                        userId = userId,
                        itemId = item.id,
                        slotId = p.slotId,
                    ),
                )
            }
        }
        terrariumRepository.save(terrarium)

        // BE-03: 응답 재조립 — terrarium 재-findByUserId + wilting 중복 조회 제거.
        // placements 는 buildTerrariumResponse 의 JPQL 이 실행 전 auto-flush 를 유발해
        // 위 재INSERT 분이 반영된 상태로 1회만 재조회된다 (id 채번 재조회는 유지 — 안전).
        return buildTerrariumResponse(userId, terrarium)
    }

    @Transactional
    fun heartClick(userId: String): HeartResponse {
        userRepository
            .findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) } // 존재 검증
        // /analyze 2026-05-18 발견 (Codex C-1): 기존 코드 `basicCoin += 1` ↔ `reward = 0.1`
        // 10× mismatch — response 가 실제 DB 누적과 불일치. spec example 도 +0.1 였으나
        // DB 컬럼 BIGINT 와 fractional 모델 충돌. autonomous default (Phase 4 pre-launch):
        //   - DB schema 유지 (BIGINT)
        //   - response 의 `reward` 와 `updatedBasicCoins` 가 실 DB state 와 정확히 일치
        //   - spec example 도 1.0 으로 정정 (별 PR)
        // 어뷰징 cooldown 정책은 별 cycle (현재 client side throttle 만 존재).
        val rewardCoins = 1L
        // 낙서장 P1: 하트 보상 = 신 substrate COIN credit 단일 SoT. credit 반환값(신 잔액)을 응답에 사용.
        val newCoinBalance =
            currencyService.credit(
                userId,
                com.terraworld.domain.currency.CurrencyCode.COIN,
                rewardCoins,
                com.terraworld.api.wallet.WalletTransactionService.REASON_RECORD_REWARD,
                "HEART",
                userId,
            )
        return HeartResponse(
            reward = rewardCoins.toDouble(),
            updatedBasicCoins = newCoinBalance.toDouble(),
        )
    }
}
