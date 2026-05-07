package com.terraworld.api.ranking

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.RankingApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.ranking.dto.RankingEntry as LocalRankingEntry
import com.terraworld.api.ranking.dto.RankingResponse as LocalRankingResponse
import io.terraworld.api.model.RankingEntry as ApiRankingEntry
import io.terraworld.api.model.RankingResponse as ApiRankingResponse

/**
 * RankingApi (1 endpoint — getMonthlyRanking) implement.
 * RankingService 는 local DTO 를 반환하므로 controller 에서 generated
 * 로 변환. type 은 generated 가 enum (ENGAGEMENT/DECORATION) 으로
 * 강제 — service 의 String 을 forValue 로 변환.
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
    ): ResponseEntity<ApiRankingResponse> {
        val local =
            rankingService.getMonthly(
                currentUserId = SecurityUtil.getCurrentUserId(),
                type = type,
                yearMonth = yearMonth,
                limit = limit,
            )
        return ResponseEntity.ok(local.toApi())
    }

    private fun LocalRankingResponse.toApi(): ApiRankingResponse =
        ApiRankingResponse(
            type = ApiRankingResponse.Type.forValue(type),
            yearMonth = yearMonth,
            propertyEntries = entries.map { it.toApi() },
            myRank = myRank,
            myScore = myScore,
        )

    private fun LocalRankingEntry.toApi(): ApiRankingEntry =
        ApiRankingEntry(
            rank = rank,
            userId = userId,
            nickname = nickname,
            score = score,
            isSelf = isSelf,
        )
}
