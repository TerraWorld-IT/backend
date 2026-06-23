package com.terraworld.api.record

import com.terraworld.api.record.dto.CreateRecordRequest
import com.terraworld.api.user.WalletBuilder
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.common.time.KstTime
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import io.terraworld.api.model.CategoryCount
import io.terraworld.api.model.CreateRecordResponse
import io.terraworld.api.model.RecordResponse
import io.terraworld.api.model.RewardInfo
import io.terraworld.api.model.StatisticsResponse
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
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
 *  - generated `RecordResponse.createdAt` 은 `OffsetDateTime` — entity 의 `LocalDateTime` 을
 *    `.atOffset(ZoneOffset.UTC)` 로 변환.
 *  - generated `RecordResponse.photoUrl` 은 `URI?` — DB 에 저장된 String 을 `URI.create()` 로 변환.
 *    잘못된 String 이 들어오면 `IllegalArgumentException` → global handler 가 500 처리 (silent loss
 *    대신 fail-fast).
 */
@Service
class RecordService(
    private val recordRepository: RecordRepository,
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val categoryRepository: CategoryRepository,
    private val inviteRepository: InviteRepository,
    private val walletBuilder: WalletBuilder,
    // UltraPlan v3 J-EVO-001 (2026-05-18): EXP 가산 후 level-up 자동 트리거
    private val userLevelService: com.terraworld.api.user.UserLevelService,
    // N2 (구현 계획서 v4): record reward 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
) {
    companion object {
        // code-review: 기록 1건당 EXP 보상 고정값. 응답 echo(experienceGained)와 실제 가산
        // (user.totalExp)이 같은 상수를 써야 reload 시 보고값과 실제가 어긋나지 않는다.
        const val EXP_PER_RECORD = 10
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

        // 일일 제한 체크
        val todayCount =
            recordRepository.countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
                userId,
                KstTime.today(),
                category.id,
            )
        if (todayCount >= category.dailyLimit) {
            throw BusinessException(
                ErrorCode.DAILY_LIMIT_EXCEEDED,
                "오늘 ${category.name} 기록 횟수(${category.dailyLimit}회)를 초과했습니다",
            )
        }

        // Joint record 검증 (지정 시)
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
                    recordedDate = KstTime.today(),
                    partnerUserId = partner?.id,
                    jointSessionId = jointSessionId,
                ),
            )
        applyReward(record, category)

        // Partner 기록 (있을 때) — partner 의 daily limit 체크 후, 미달이면 보상 가산
        if (partner != null) {
            val partnerTodayCount =
                recordRepository.countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
                    partner.id,
                    KstTime.today(),
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
                            recordedDate = KstTime.today(),
                            partnerUserId = userId,
                            jointSessionId = jointSessionId,
                        ),
                    )
                applyReward(partnerRecord, category)
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
                    memo = record.memo,
                    duration = record.durationMinutes,
                    photoUrl = record.photoUrl?.let { URI.create(it) },
                ),
            reward =
                RewardInfo(
                    basicCoins = category.baseCoinReward,
                    categoryTokens = category.baseTokenReward,
                    experienceGained = EXP_PER_RECORD,
                ),
            updatedCurrency = walletBuilder.build(userId, user),
        )
    }

    /**
     * N2 (구현 계획서 v4): record reward 적용 + wallet_transactions 원장 INSERT x2
     * (BASIC_COIN + CATEGORY_TOKEN). spec records.yaml:73 명시. EXP 는 wallet currency 가
     * 아니므로 ledger 미기록.
     */
    private fun applyReward(
        record: ActivityRecord,
        category: com.terraworld.domain.category.Category,
    ) {
        val user = record.user
        user.basicCoin += category.baseCoinReward
        user.totalExp += EXP_PER_RECORD
        userRepository.save(user)

        // wallet ledger — BASIC_COIN row
        walletTransactionService.append(
            user = user,
            currencyType = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_BASIC_COIN,
            amount = category.baseCoinReward.toLong(),
            balanceAfter = user.basicCoin,
            reason = com.terraworld.api.wallet.WalletTransactionService.REASON_RECORD_REWARD,
            referenceId = record.id,
        )

        // UltraPlan v3 J-EVO-001 (Codex pre-audit HIGH):
        // EXP 가산 직후 level-up 자동 체크. level_config 임계값 (V12 seed, 선형 N×100, 상한 10)
        // 도달 시 user.level 자동 갱신 + AuditService LEVEL_UP 기록.
        // Decay 없음 (광고 복구·환불·시들기 모두 EXP 변동 X — 사용자 박탈감 회피).
        userLevelService.checkAndApplyLevelUp(user)

        val token =
            userTokenRepository.findByUserIdAndCategoryId(user.id, category.id).orElseGet {
                // Custom category 의 첫 적립 시점에 row 생성
                userTokenRepository.save(
                    com.terraworld.domain.user.UserToken(
                        user = user,
                        category = category,
                        amount = 0,
                    ),
                )
            }
        token.amount += category.baseTokenReward
        userTokenRepository.save(token)

        // wallet ledger — CATEGORY_TOKEN row
        walletTransactionService.append(
            user = user,
            currencyType = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_CATEGORY_TOKEN,
            amount = category.baseTokenReward.toLong(),
            balanceAfter = token.amount.toLong(),
            reason = com.terraworld.api.wallet.WalletTransactionService.REASON_RECORD_REWARD,
            category = category,
            referenceId = record.id,
        )
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
            val records = recordRepository.findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(userId, from, to)
            val responses = records.map { it.toResponse() }
            return PageImpl(responses, pageable, responses.size.toLong())
        }
        return recordRepository
            .findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, pageable)
            .map { it.toResponse() }
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

        return StatisticsResponse(
            todayRecords = recordRepository.countByUserIdAndRecordedDateAndIsDeletedFalse(userId, today),
            thisWeekRecords = recordRepository.countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(userId, weekAgo),
            totalRecords = recordRepository.countByUserIdAndIsDeletedFalse(userId),
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
            memo = memo,
            duration = durationMinutes,
            photoUrl = photoUrl?.let { URI.create(it) },
        )
}
