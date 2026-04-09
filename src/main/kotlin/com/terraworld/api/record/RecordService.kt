package com.terraworld.api.record

import com.terraworld.api.record.dto.*
import com.terraworld.api.user.dto.CurrencyResponse
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.record.ActivityRecord
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate

@Service
class RecordService(
    private val recordRepository: RecordRepository,
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val categoryRepository: CategoryRepository,
) {

    @Transactional
    fun createRecord(userId: Long, request: CreateRecordRequest): CreateRecordResponse {
        val user = userRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val category = categoryRepository.findById(request.categoryId)
            .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }

        // 일일 제한 체크
        val todayCount = recordRepository.countByUserIdAndRecordedDateAndCategoryIdAndIsDeletedFalse(
            userId, LocalDate.now(), category.id
        )
        if (todayCount >= category.dailyLimit) {
            throw BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED,
                "오늘 ${category.name} 기록 횟수(${category.dailyLimit}회)를 초과했습니다")
        }

        // 기록 생성
        val record = recordRepository.save(
            ActivityRecord(
                user = user, category = category,
                memo = request.note, recordedDate = LocalDate.now(),
            )
        )

        // 보상 지급
        user.basicCoin += category.baseCoinReward
        user.totalExp += 10
        userRepository.save(user)

        val token = userTokenRepository.findByUserIdAndCategoryId(userId, category.id)
            .orElseThrow()
        token.amount += category.baseTokenReward
        userTokenRepository.save(token)

        // 응답 구성
        val tokens = userTokenRepository.findAllByUserId(userId)
        val tokenMap = tokens.associate { it.category.id to it.amount }

        return CreateRecordResponse(
            record = RecordResponse(
                id = record.id, categoryId = category.id,
                categoryName = category.name, categoryEmoji = category.emoji,
                memo = record.memo, duration = request.duration,
                recordedDate = record.recordedDate, createdAt = record.createdAt,
            ),
            reward = RewardInfo(
                basicCoins = category.baseCoinReward,
                categoryTokens = category.baseTokenReward,
                experienceGained = 10,
            ),
            updatedCurrency = CurrencyResponse(
                basicCoins = user.basicCoin.toDouble(),
                specialCoins = user.specialCoin.toDouble(),
                walkTokens = (tokenMap[1L] ?: 0).toDouble(),
                readTokens = (tokenMap[2L] ?: 0).toDouble(),
                runTokens = (tokenMap[3L] ?: 0).toDouble(),
                drawTokens = (tokenMap[4L] ?: 0).toDouble(),
            ),
        )
    }

    @Transactional(readOnly = true)
    fun getRecords(userId: Long, categoryId: Long?, year: Int?, month: Int?, pageable: Pageable): Page<RecordResponse> {
        if (year != null && month != null) {
            val from = LocalDate.of(year, month, 1)
            val to = from.withDayOfMonth(from.lengthOfMonth())
            val records = recordRepository.findAllByUserIdAndRecordedDateBetweenAndIsDeletedFalse(userId, from, to)
            // Convert to page manually for calendar view
            val responses = records.map { it.toResponse() }
            return org.springframework.data.domain.PageImpl(responses, pageable, responses.size.toLong())
        }
        return recordRepository.findAllByUserIdAndIsDeletedFalseOrderByCreatedAtDesc(userId, pageable)
            .map { it.toResponse() }
    }

    @Transactional(readOnly = true)
    fun getStatistics(userId: Long): StatisticsResponse {
        val today = LocalDate.now()
        val weekAgo = today.minusDays(7)
        val categories = categoryRepository.findAllByIsActiveTrue()
        val categoryCounts = recordRepository.countByCategoryGrouped(userId)
        val countMap = categoryCounts.associate { (it[0] as Long) to (it[1] as Long) }

        return StatisticsResponse(
            todayRecords = recordRepository.countByUserIdAndRecordedDateAndIsDeletedFalse(userId, today),
            thisWeekRecords = recordRepository.countByUserIdAndRecordedDateGreaterThanEqualAndIsDeletedFalse(userId, weekAgo),
            totalRecords = recordRepository.countByUserIdAndIsDeletedFalse(userId),
            byCategory = categories.map { cat ->
                CategoryCount(
                    categoryId = cat.id, categoryName = cat.name,
                    emoji = cat.emoji, count = countMap[cat.id] ?: 0,
                )
            },
        )
    }

    @Transactional
    fun deleteRecord(userId: Long, recordId: Long) {
        val record = recordRepository.findById(recordId)
            .orElseThrow { BusinessException(ErrorCode.RECORD_NOT_FOUND) }
        if (record.user.id != userId) throw BusinessException(ErrorCode.FORBIDDEN)
        record.isDeleted = true
        recordRepository.save(record)
    }

    private fun ActivityRecord.toResponse() = RecordResponse(
        id = id, categoryId = category.id,
        categoryName = category.name, categoryEmoji = category.emoji,
        memo = memo, duration = null,
        recordedDate = recordedDate, createdAt = createdAt,
    )
}
