package com.terraworld.api.record

import com.terraworld.api.record.dto.*
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.web.PageableDefault
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "Record", description = "활동 기록 API")
@RestController
@RequestMapping("/api/v1/records")
class RecordController(
    private val recordService: RecordService,
) {

    @Operation(summary = "활동 기록 생성", description = "기록 생성 후 보상을 자동 지급합니다")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun createRecord(@Valid @RequestBody request: CreateRecordRequest): CreateRecordResponse =
        recordService.createRecord(SecurityUtil.getCurrentUserId(), request)

    @Operation(summary = "기록 목록 조회")
    @GetMapping
    fun getRecords(
        @Parameter(description = "카테고리 ID") @RequestParam(required = false) categoryId: Long?,
        @Parameter(description = "연도") @RequestParam(required = false) year: Int?,
        @Parameter(description = "월") @RequestParam(required = false) month: Int?,
        @PageableDefault(size = 20) pageable: Pageable,
    ): Page<RecordResponse> =
        recordService.getRecords(SecurityUtil.getCurrentUserId(), categoryId, year, month, pageable)

    @Operation(summary = "활동 통계")
    @GetMapping("/statistics")
    fun getStatistics(): StatisticsResponse =
        recordService.getStatistics(SecurityUtil.getCurrentUserId())

    @Operation(summary = "기록 삭제 (soft delete)")
    @DeleteMapping("/{recordId}")
    fun deleteRecord(@PathVariable recordId: Long): Map<String, String> {
        recordService.deleteRecord(SecurityUtil.getCurrentUserId(), recordId)
        return mapOf("message" to "기록이 삭제되었습니다")
    }
}
