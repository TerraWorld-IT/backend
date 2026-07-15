package com.terraworld.api.record

import com.terraworld.api.record.dto.CreateRecordRequest
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.DailyType
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.CategoryCount
import io.terraworld.api.model.CreateRecordResponse
import io.terraworld.api.model.RecordResponse
import io.terraworld.api.model.RewardInfo
import io.terraworld.api.model.StatisticsResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * ARCH-008-phase-11: 반환 타입을 generated DTO 직접 사용. local Record DTO (CreateRecordResponse /
 * RecordResponse / StatisticsResponse / CategoryCount / RewardInfo) 삭제.
 *
 * 주의:
 * - generated `RecordResponse.createdAt` 은 `OffsetDateTime` — entity 의 `LocalDateTime` 을
 * `.atOffset(ZoneOffset.UTC)` 로 변환.
 * - generated `RecordResponse.photoUrl` 은 `URI?` — DB 에 저장된 String 을 `URI.create` 로 변환.
 * 잘못된 String 이 들어오면 `IllegalArgumentException` → global handler 가 500 처리 (silent loss
 * 대신 fail-fast).
 */
@Service
class RecordService(
    private val recordRepository: RecordRepository,
    private val userRepository: UserRepository,
    // 낙서장 P1 cutover(dual-write): 기록 보상 = 코인 + 카테고리 토큰(시스템=원소, 커스텀=코인) 정규화 잔액에도 반영
    private val currencyService: com.terraworld.api.currency.CurrencyService,
    private val categoryRepository: CategoryRepository,
    private val inviteRepository: InviteRepository,
    private val walletBuilder: WalletBuilder,
    // N2 (구현 계획서 v4): record reward 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    // 낙서장 P3: 일상 기록 시 육성 연속 진행 (하루 1회, 전 종)
    private val growthService: com.terraworld.api.growth.GrowthService,
) {
    companion object {
        const val RECORD_REASON = "RECORD_REWARD"
    }

    @Transactional
    fun createRecord(
        userId: String,
        request: CreateRecordRequest,
    ): CreateRecordResponse {
        val user =
            userRepository
                .findById(userId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val category =
            categoryRepository
                .findById(request.categoryId)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }

        // 커스텀 카테고리는 본인만 사용 가능
        if (category.isCustom && category.ownerUserId != userId) {
            throw BusinessException(ErrorCode.CATEGORY_NOT_FOUND, "본인 카테고리가 아닙니다")
        }

        // R7-DAILYTYPE: 일상 기록 보상은 dailyType 고정(DailyType.currencyCode/coinReward, category 무관)이라,
        //   dailyType 을 canonical 시스템 카테고리로 강제하지 않으면 (userId,date,category) 일일 한도가 dailyType 별
        //   cap 을 못 걸어 여러 category(특히 사용자 생성 custom)로 같은 dailyType 토큰을 반복 민팅할 수 있다.
        //   → dailyType 지정 시 그 canonical 비-custom 시스템 카테고리만 허용(FE 는 이미 이 매핑으로 전송).
        //   각 dailyType ↔ 단일 시스템 카테고리이므로 per-category dailyLimit 이 dailyType cap 을 올바르게 강제.
        request.dailyType?.let { dt ->
            val canonicalName =
                when (dt) {
                    DailyType.PHOTO -> "산책"
                    DailyType.DIARY -> "독서"
                    DailyType.FOCUS -> "러닝"
                    DailyType.DISTANCE -> "낙서"
                }
            if (category.isCustom || category.name != canonicalName) {
                throw BusinessException(ErrorCode.INVALID_INPUT, "일상 기록은 '$canonicalName' 카테고리에만 기록할 수 있어요")
            }
        }

        val today = KstTime.today()

        // Joint record 검증 (지정 시) — advisory lock 획득 전에 partner 를 먼저 확정해야
        // self+partner 두 lock 을 요청 방향 무관 동일(정렬) 순서로 잡아 ABBA 데드락을 막을 수 있음.
        val partner: User? =
            request.partnerUserId
                ?.takeIf { it.isNotBlank() }
                ?.let { partnerId ->
                    if (partnerId == userId) throw BusinessException(ErrorCode.INVALID_PARTNER, "본인을 partner 로 지정할 수 없습니다")
                    val target =
                        userRepository
                            .findById(partnerId)
                            .orElseThrow { BusinessException(ErrorCode.INVALID_PARTNER, "존재하지 않는 사용자입니다") }
                    if (!inviteRepository.existsAcceptedBetween(userId, partnerId)) {
                        throw BusinessException(ErrorCode.INVALID_PARTNER, "친구로 등록되지 않은 사용자입니다")
                    }
                    target
                }

        // H4 + R2-REC-DEADLOCK + R2-GROWTH-VERSION: per-(user|day) advisory lock (같은 tx, 종료 시 자동 해제).
        //  - 키를 category 별이 아니라 user-day 로 잡는 이유(R2-GROWTH-VERSION): createRecord 는 category 별
        //    daily-limit(count-then-insert) 뿐 아니라 user 전역 side-effect advanceAllStreaks(growth_instances,
        //    @Version RMW)도 수행한다. category-lock 이면 서로 다른 카테고리 동시 기록이 직렬화되지 않아 growth
        //    @Version 충돌(409+롤백)이 난다. user-day lock 은 한 사용자의 모든 카테고리 기록을 직렬화 → daily-limit
        //    TOCTOU + growth 진행(멱등 no-op)을 함께 보장.
        //  - joint 는 self+partner 2개 lock 이 필요 — 정렬된 canonical 순서로 획득해 A→B/B→A 동시 요청의
        //    ABBA 데드락(SQLSTATE 40P01)을 차단. 모든 count/insert/growth 이전에 확보.
        val lockKeys =
            buildList {
                add("record|$userId|$today")
                partner?.let { add("record|${it.id}|$today") }
            }.sorted()
        lockKeys.forEach { recordRepository.acquireRecordDailyLock(it) }

        // 일일 제한 체크 (self) — REC-DELETE-REFARM: soft-delete 포함 전건 카운트.
        //  deleteRecord 는 이미 지급된 보상을 회수하지 않으므로, IsDeletedFalse 카운트로 cap 을 걸면
        //  create→delete→create 로 슬롯을 되돌려 무한 재민팅이 가능하다. cap 은 "이 (user,category,date) 로
        //  지금까지 민팅한 횟수" 이므로 삭제된 기록도 세어 재민팅을 차단.
        val todayCount =
            recordRepository.countByUserIdAndRecordedDateAndCategoryId(
                userId,
                today,
                category.id,
            )
        if (todayCount >= category.dailyLimit) {
            throw BusinessException(
                ErrorCode.DAILY_LIMIT_EXCEEDED,
                "오늘 ${category.name} 기록 횟수(${category.dailyLimit}회)를 초과했습니다",
            )
        }

        val jointSessionId: UUID? = if (partner != null) UUID.randomUUID() else null

        // 본인 기록
        val record =
            recordRepository.save(
                ActivityRecord(
                    user = user,
                    category = category,
                    memo = request.note,
                    durationMinutes = request.duration,
                    photoUrl = request.photoUrl,
                    recordedDate = today,
                    dailyType = request.dailyType,
                    partnerUserId = partner?.id,
                    jointSessionId = jointSessionId,
                ),
            )
        val reward = applyReward(record, category)
        // 낙서장 P3: 기록 → 육성 연속 진행 (하루 1회, 전 종). 육성 실패/부스터는 GrowthService 내부.
        growthService.advanceAllStreaks(userId)

        // Partner 기록 (있을 때) — partner 의 daily limit 체크 후, 미달이면 보상 가산
        // (partner advisory lock 은 위 lockKeys 정렬 획득에 이미 포함 — 여기서 재획득하지 않음)
        if (partner != null) {
            // REC-DELETE-REFARM: partner cap 도 soft-delete 포함 전건 카운트 (재민팅 차단, self 와 동일).
            val partnerTodayCount =
                recordRepository.countByUserIdAndRecordedDateAndCategoryId(
                    partner.id,
                    today,
                    category.id,
                )
            if (partnerTodayCount < category.dailyLimit) {
                val partnerRecord =
                    recordRepository.save(
                        ActivityRecord(
                            user = partner,
                            category = category,
                            memo = request.note,
                            durationMinutes = request.duration,
                            photoUrl = request.photoUrl,
                            recordedDate = today,
                            dailyType = request.dailyType,
                            partnerUserId = userId,
                            jointSessionId = jointSessionId,
                        ),
                    )
                applyReward(partnerRecord, category)
                // 리뷰 Q#5: joint record 시 partner 육성도 진행 (본인만 진행하던 결함)
                growthService.advanceAllStreaks(partner.id)
            }
        }

        return CreateRecordResponse(
            record =
                RecordResponse(
                    id = record.id,
                    categoryId = category.id,
                    categoryName = category.name,
                    recordedDate = record.recordedDate,
                    createdAt = record.createdAt.atOffset(ZoneOffset.UTC),
                    categoryEmoji = category.emoji,
                    dailyType = record.dailyType?.toResponseEnum(),
                    memo = record.memo,
                    duration = record.durationMinutes,
                    photoUrl = record.photoUrl?.let { URI.create(it) },
                ),
            reward =
                RewardInfo(
                    basicCoins = reward.first,
                    categoryTokens = reward.second,
                ),
            // 낙서장 P1 read-cutover: WalletBuilder 가 신 substrate 를 읽으므로 단일 축(리뷰 A#4 해소)
            updatedCurrency = walletBuilder.build(userId, user),
        )
    }

    /**
     * 보상 적용 (N2: wallet_transactions 원장 기록 포함). dailyType 지정 시 낙서장 일상 보상
     * (코인 + 매칭 토큰 신 substrate), 미지정 시 기존 카테고리 보상(구 저장 dual-write). 실 지급 (coin, token) 반환.
     */
    private fun applyReward(
        record: ActivityRecord,
        category: com.terraworld.domain.category.Category,
    ): Pair<Int, Int> {
        val dailyType = record.dailyType
        return if (dailyType != null) {
            applyDailyReward(record, dailyType)
        } else {
            applyCategoryReward(record, category)
        }
    }

    /** 낙서장 일상 보상: 코인 + dailyType 매칭 토큰(DEW/SUN/BOLT/WIND) — 신 substrate 단일 SoT. */
    private fun applyDailyReward(
        record: ActivityRecord,
        dailyType: com.terraworld.domain.record.DailyType,
    ): Pair<Int, Int> {
        val userId = record.user.id
        val coin = dailyType.coinReward
        val token = dailyType.tokenReward
        currencyService.credit(userId, com.terraworld.domain.currency.CurrencyCode.COIN, coin, RECORD_REASON, "RECORD", record.id.toString())
        currencyService.credit(userId, dailyType.currencyCode, token, RECORD_REASON, "RECORD", record.id.toString())
        return coin.toInt() to token.toInt()
    }

    /** 카테고리 보상(coin + category 토큰) — 신 substrate 단일 SoT (구 저장 dual-write 제거, V32). */
    private fun applyCategoryReward(
        record: ActivityRecord,
        category: com.terraworld.domain.category.Category,
    ): Pair<Int, Int> {
        val userId = record.user.id
        currencyService.credit(userId, com.terraworld.domain.currency.CurrencyCode.COIN, category.baseCoinReward.toLong(), RECORD_REASON, "RECORD", record.id.toString())
        // 시스템 카테고리(1~4) → 원소 토큰(단일 SoT), 커스텀 → 전용 토큰 없음(정책 #1)
        val elementToken =
            com.terraworld.domain.currency.CurrencyCode
                .elementTokenForCategory(category.id)
        return if (elementToken != null) {
            currencyService.credit(userId, elementToken, category.baseTokenReward.toLong(), RECORD_REASON, "RECORD", record.id.toString())
            category.baseCoinReward to category.baseTokenReward
        } else {
            // 커스텀 카테고리: 원소 토큰 없음 → 토큰 보상분을 COIN 으로 지급, 응답 categoryTokens=0 (정직 표기).
            // silent COIN default 로 "토큰 획득" 이라 응답하는데 지갑엔 토큰이 없는 divergence 를 차단.
            currencyService.credit(userId, com.terraworld.domain.currency.CurrencyCode.COIN, category.baseTokenReward.toLong(), RECORD_REASON, "RECORD", record.id.toString())
            (category.baseCoinReward + category.baseTokenReward) to 0
        }
    }

    @Transactional(readOnly = true)
    fun getRecords(
        userId: String,
        categoryId: Long?,
        year: Int?,
        month: Int?,
        pageable: Pageable,
    ): Page<RecordResponse> {
        if (year != null && month != null) {
            val from = LocalDate.of(year, month, 1)
            val to = from.withDayOfMonth(from.lengthOfMonth())
            // L3: categoryId 지정 시 카테고리 필터 적용 (RecordApi categoryId query 계약 정합).
            // BE-16: 월 조회도 pageable 적용 (기존: 전건 List + PageImpl 로 pageable 무시 — 계약 위반).
            return if (categoryId != null) {
                recordRepository
                    .findAllByUserIdAndCategoryIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(userId, categoryId, from, to, pageable)
                    .map { it.toResponse() }
            } else {
                recordRepository
                    .findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalseOrderByCreatedAtDesc(userId, from, to, pageable)
                    .map { it.toResponse() }
            }
        }
        // L3: categoryId 지정 시 카테고리 필터 적용.
        return if (categoryId != null) {
            recordRepository
                .findAllByUserIdAndCategoryIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, categoryId, pageable)
                .map { it.toResponse() }
        } else {
            recordRepository
                .findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, pageable)
                .map { it.toResponse() }
        }
    }

    @Transactional(readOnly = true)
    fun getStatistics(userId: String): StatisticsResponse {
        val today = KstTime.today()
        val weekAgo = today.minusDays(7)
        val categories =
            categoryRepository.findAllByIsActiveTrueAndIsCustomFalse() +
                categoryRepository.findAllByOwnerUserIdAndIsActiveTrue(userId)
        val categoryCounts = recordRepository.countByCategoryGrouped(userId)
        val countMap = categoryCounts.associate { (it[0] as Long) to (it[1] as Long) }

        // ADD-BE-02 (2026-07-15 성능 감사): today/week/total 3 COUNT 쿼리 → 조건부 집계 1쿼리.
        // SUM(CASE WHEN) 은 기록 0건 시 null — 0 으로 정규화.
        val summary = recordRepository.countStatisticsSummary(userId, today, weekAgo)

        return StatisticsResponse(
            todayRecords = summary.todayCount ?: 0L,
            thisWeekRecords = summary.weekCount ?: 0L,
            totalRecords = summary.totalCount,
            byCategory =
                categories.map { cat ->
                    CategoryCount(
                        categoryId = cat.id,
                        categoryName = cat.name,
                        count = countMap[cat.id] ?: 0,
                        emoji = cat.emoji,
                    )
                },
        )
    }

    @Transactional
    fun deleteRecord(
        userId: String,
        recordId: Long,
    ) {
        val record =
            recordRepository
                .findById(recordId)
                .orElseThrow { BusinessException(ErrorCode.RECORD_NOT_FOUND) }
        if (record.user.id != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        record.isDeleted = true
        recordRepository.save(record)
    }

    private fun ActivityRecord.toResponse() =
        RecordResponse(
            id = id,
            categoryId = category.id,
            categoryName = category.name,
            recordedDate = recordedDate,
            createdAt = createdAt.atOffset(ZoneOffset.UTC),
            categoryEmoji = category.emoji,
            dailyType = dailyType?.toResponseEnum(),
            memo = memo,
            duration = durationMinutes,
            photoUrl = photoUrl?.let { URI.create(it) },
        )

    // categoryId/categoryName 은 보상 라우팅/일일 한도용 canonical 값으로 그대로 두고,
    // 화면 표시는 dailyType 을 우선하도록 별도 필드로 노출 (RecordResponse.yaml 참조).
    // 도메인 DailyType 과 생성된 RecordResponse.DailyType 은 이름이 동일해 name 기준 매핑.
    private fun DailyType.toResponseEnum(): RecordResponse.DailyType = RecordResponse.DailyType.valueOf(name)
}
