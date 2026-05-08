package com.terraworld.api.record

import com.terraworld.api.shared.dto.toApi
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
import io.terraworld.api.model.CategoryCount as ApiCategoryCount
import io.terraworld.api.model.CreateRecordRequest as ApiCreateRecordRequest
import io.terraworld.api.model.CreateRecordResponse as ApiCreateRecordResponse
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
    private val photoUrlValidator: com.terraworld.api.upload.PhotoUrlValidator,
) : RecordApi {
    @Operation(
        summary = "활동 기록 생성",
        description = "기록 생성 후 보상을 자동 지급합니다",
    )
    override fun createRecord(
        @Valid createRecordRequest: ApiCreateRecordRequest,
    ): ResponseEntity<ApiCreateRecordResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        // SF-005 follow-up: photoUrl 도메인 화이트리스트 (scheme/host/data MIME)
        val photoUrlString = createRecordRequest.photoUrl?.toString()
        photoUrlValidator.requireAllowed(photoUrlString)
        val local =
            recordService.createRecord(
                userId = userId,
                request =
                    LocalCreateRecordRequest(
                        categoryId = createRecordRequest.categoryId,
                        duration = createRecordRequest.duration,
                        note = createRecordRequest.note,
                        photoUrl = photoUrlString,
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
            // SF-004/005: 이전엔 하드코딩 null. 이제 service 가 ActivityRecord.photoUrl
            // 을 RecordResponse.photoUrl 로 채워주므로 그대로 매핑.
            // 잘못된 String 이 들어오면 URI.create 가 IllegalArgumentException →
            // global handler 가 500 으로 매핑 (silent loss 대신 fail-fast).
            photoUrl = photoUrl?.let { java.net.URI.create(it) },
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
}
