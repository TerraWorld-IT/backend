package com.terraworld.api.record

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RecordApi
import io.terraworld.api.model.MessageResponse
import io.terraworld.api.model.PagedRecordResponse
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset
import com.terraworld.api.record.dto.CategoryCount as LocalCategoryCount
import com.terraworld.api.record.dto.CreateRecordRequest as LocalCreateRecordRequest
import com.terraworld.api.record.dto.CreateRecordResponse as LocalCreateRecordResponse
import com.terraworld.api.record.dto.RecordResponse as LocalRecordResponse
import com.terraworld.api.record.dto.RewardInfo as LocalRewardInfo
import com.terraworld.api.record.dto.StatisticsResponse as LocalStatisticsResponse
import com.terraworld.api.user.dto.CategoryTokenAmount as LocalCategoryTokenAmount
import com.terraworld.api.user.dto.CurrencyResponse as LocalCurrencyResponse
import io.terraworld.api.model.CategoryCount as ApiCategoryCount
import io.terraworld.api.model.CategoryTokenAmount as ApiCategoryTokenAmount
import io.terraworld.api.model.CreateRecordRequest as ApiCreateRecordRequest
import io.terraworld.api.model.CreateRecordResponse as ApiCreateRecordResponse
import io.terraworld.api.model.CurrencyResponse as ApiCurrencyResponse
import io.terraworld.api.model.RecordResponse as ApiRecordResponse
import io.terraworld.api.model.RewardInfo as ApiRewardInfo
import io.terraworld.api.model.StatisticsResponse as ApiStatisticsResponse

/**
 * RecordApi (4 endpoints) implement. spec PR #12 의 tag 분리 (Record ↔
 * Upload) 결과 RecordApi 는 활동 기록 CRUD + 통계만 책임.
 *
 * Pageable 처리
 * - generated `listRecords` 는 explicit `page` / `size` 파라미터.
 *   Spring Data `Pageable` 을 controller 안에서 `PageRequest.of(page, size)`
 *   로 빌드해 RecordService 에 전달 — service 시그니처 변경 0.
 *
 * DTO mapping
 * - RecordService 가 local DTO 를 반환하므로 controller 에서 generated
 *   DTO 로 변환. `LocalDateTime` → `OffsetDateTime` 은 UTC offset 가정.
 *   `String?` photoUrl → `URI?` (clients 직접 들어가는 경로라 invalid 시
 *   400 으로 떨어지도록 URI.create 로 변환 — generated request 는 이미
 *   URI 로 받아서 service 호출 시 String 화).
 */
@Tag(name = "Record", description = "활동 기록 API")
@RestController
@RequestMapping("/api/v1")
class RecordController(
    private val recordService: RecordService,
) : RecordApi {
    @Operation(
        summary = "활동 기록 생성",
        description = "기록 생성 후 보상을 자동 지급합니다",
    )
    override fun createRecord(
        @Valid createRecordRequest: ApiCreateRecordRequest,
    ): ResponseEntity<ApiCreateRecordResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val local =
            recordService.createRecord(
                userId = userId,
                request =
                    LocalCreateRecordRequest(
                        categoryId = createRecordRequest.categoryId,
                        duration = createRecordRequest.duration,
                        note = createRecordRequest.note,
                        photoUrl = createRecordRequest.photoUrl?.toString(),
                        partnerUserId = createRecordRequest.partnerUserId,
                    ),
            )
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(local.toApi())
    }

    @Operation(summary = "기록 목록 조회")
    override fun listRecords(
        categoryId: Long?,
        year: Int?,
        month: Int?,
        page: Int,
        size: Int,
    ): ResponseEntity<PagedRecordResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val pageable = PageRequest.of(page, size)
        val resultPage = recordService.getRecords(userId, categoryId, year, month, pageable)
        return ResponseEntity.ok(
            PagedRecordResponse(
                content = resultPage.content.map { it.toApi() },
                page = resultPage.number,
                propertySize = resultPage.size,
                totalElements = resultPage.totalElements,
                totalPages = resultPage.totalPages,
            ),
        )
    }

    @Operation(summary = "활동 통계")
    override fun getRecordStatistics(): ResponseEntity<ApiStatisticsResponse> {
        val local = recordService.getStatistics(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(local.toApi())
    }

    @Operation(summary = "기록 삭제 (soft delete)")
    override fun deleteRecord(recordId: Long): ResponseEntity<MessageResponse> {
        recordService.deleteRecord(SecurityUtil.getCurrentUserId(), recordId)
        return ResponseEntity.ok(MessageResponse(message = "기록이 삭제되었습니다"))
    }

    private fun LocalCreateRecordResponse.toApi(): ApiCreateRecordResponse =
        ApiCreateRecordResponse(
            record = record.toApi(),
            reward = reward.toApi(),
            updatedCurrency = updatedCurrency.toApi(),
        )

    private fun LocalRecordResponse.toApi(): ApiRecordResponse =
        ApiRecordResponse(
            id = id,
            categoryId = categoryId,
            categoryName = categoryName,
            recordedDate = recordedDate,
            createdAt = createdAt.atOffset(ZoneOffset.UTC),
            categoryEmoji = categoryEmoji,
            memo = memo,
            duration = duration,
            // local RecordResponse 는 photoUrl 미보유 — 후속 슬라이스에서 service
            // 가 generated 직접 반환하도록 마이그레이션 시 채워질 예정.
            photoUrl = null,
        )

    private fun LocalRewardInfo.toApi(): ApiRewardInfo =
        ApiRewardInfo(
            basicCoins = basicCoins,
            categoryTokens = categoryTokens,
            experienceGained = experienceGained,
        )

    private fun LocalStatisticsResponse.toApi(): ApiStatisticsResponse =
        ApiStatisticsResponse(
            todayRecords = todayRecords,
            thisWeekRecords = thisWeekRecords,
            totalRecords = totalRecords,
            byCategory = byCategory.map { it.toApi() },
        )

    private fun LocalCategoryCount.toApi(): ApiCategoryCount =
        ApiCategoryCount(
            categoryId = categoryId,
            categoryName = categoryName,
            emoji = emoji,
            count = count,
        )

    private fun LocalCurrencyResponse.toApi(): ApiCurrencyResponse =
        ApiCurrencyResponse(
            basicCoins = basicCoins,
            specialCoins = specialCoins,
            walkTokens = walkTokens,
            readTokens = readTokens,
            runTokens = runTokens,
            drawTokens = drawTokens,
            categoryTokens = categoryTokens.map { it.toApi() },
        )

    private fun LocalCategoryTokenAmount.toApi(): ApiCategoryTokenAmount =
        ApiCategoryTokenAmount(
            categoryId = categoryId,
            categoryName = categoryName,
            amount = amount,
        )
}
