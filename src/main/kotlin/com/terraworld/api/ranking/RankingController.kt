package com.terraworld.api.ranking

import com.terraworld.api.ranking.dto.RankingResponse
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 월간 랭킹 컨트롤러.
 *
 * spec : openapi/spec/paths/rankings-monthly.yaml
 *
 * 현재는 generated openapi-backend interface 가 도착하기 전이라 plain
 * controller. spec sync 가 완료되면 `RankingApi` interface 를 implement
 * 하도록 마이그레이션 (composite build 의 컴파일 타임 정합 보장).
 */
@RestController
@RequestMapping("/api/v1/rankings")
@Tag(name = "Ranking", description = "월간 랭킹 API (참여 / 꾸미기 — Phase 3)")
class RankingController(
    private val rankingService: RankingService,
) {
    @GetMapping("/monthly")
    fun getMonthly(
        @RequestParam type: String,
        @RequestParam(required = false) yearMonth: String?,
        @RequestParam(required = false, defaultValue = "50") limit: Int,
    ): RankingResponse =
        rankingService.getMonthly(
            currentUserId = SecurityUtil.getCurrentUserId(),
            type = type,
            yearMonth = yearMonth,
            limit = limit,
        )
}
