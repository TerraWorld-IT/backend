package com.terraworld.api.record

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RecordApi
import io.terraworld.api.model.CreateRecordResponse
import io.terraworld.api.model.MessageResponse
import io.terraworld.api.model.PagedRecordResponse
import io.terraworld.api.model.StatisticsResponse
import jakarta.validation.Valid
import org.springframework.data.domain.PageRequest
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.record.dto.CreateRecordRequest as LocalCreateRecordRequest
import io.terraworld.api.model.CreateRecordRequest as ApiCreateRecordRequest

/**
 * RecordApi (4 endpoints) implement.
 *
 * ARCH-008-phase-11 후: RecordService 가 generated DTO 직접 반환. controller 매퍼 0건.
 * Pageable 처리 + photoUrl 검증만 controller 책임.
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
    ): ResponseEntity<CreateRecordResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        // SF-005 follow-up: photoUrl 도메인 화이트리스트 (scheme/host/data MIME)
        val photoUrlString = createRecordRequest.photoUrl?.toString()
        photoUrlValidator.requireAllowed(photoUrlString)
        val response =
            recordService.createRecord(
                userId = userId,
                request =
                    LocalCreateRecordRequest(
                        categoryId = createRecordRequest.categoryId,
                        dailyType =
                            createRecordRequest.dailyType?.let {
                                com.terraworld.domain.record.DailyType
                                    .valueOf(it.value)
                            },
                        duration = createRecordRequest.duration,
                        note = createRecordRequest.note,
                        photoUrl = photoUrlString,
                        partnerUserId = createRecordRequest.partnerUserId,
                    ),
            )
        return ResponseEntity.status(HttpStatus.CREATED).body(response)
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
                content = resultPage.content,
                page = resultPage.number,
                propertySize = resultPage.size,
                totalElements = resultPage.totalElements,
                totalPages = resultPage.totalPages,
            ),
        )
    }

    @Operation(summary = "활동 통계")
    override fun getRecordStatistics(): ResponseEntity<StatisticsResponse> {
        val response = recordService.getStatistics(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "기록 삭제 (soft delete)")
    override fun deleteRecord(recordId: Long): ResponseEntity<MessageResponse> {
        recordService.deleteRecord(SecurityUtil.getCurrentUserId(), recordId)
        return ResponseEntity.ok(MessageResponse(message = "기록이 삭제되었습니다"))
    }
}
