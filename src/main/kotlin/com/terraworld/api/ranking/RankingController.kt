package com.terraworld.api.ranking

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RankingApi
import io.terraworld.api.model.RankingResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * RankingApi (1 endpoint — getMonthlyRanking) implement.
 *
 * ARCH-008-phase-9 후: RankingService 가 generated [RankingResponse] 직접 반환.
 * controller 매퍼 제거.
 */
@Tag(name = "Ranking", description = "월간 랭킹 API (참여 / 꾸미기 — Phase 3)")
@RestController
@RequestMapping("/api/v1")
class RankingController(
    private val rankingService: RankingService,
) : RankingApi {
    @Operation(summary = "월간 랭킹 조회")
    override fun getMonthlyRanking(
        type: String,
        yearMonth: String?,
        limit: Int,
    ): ResponseEntity<RankingResponse> {
        val response =
            rankingService.getMonthly(
                currentUserId = SecurityUtil.getCurrentUserId(),
                type = type,
                yearMonth = yearMonth,
                limit = limit,
            )
        return ResponseEntity.ok(response)
    }
}
