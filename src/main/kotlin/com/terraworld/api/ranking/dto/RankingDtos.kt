package com.terraworld.api.ranking.dto

import io.swagger.v3.oas.annotations.media.Schema

@Schema(description = "랭킹 항목")
data class RankingEntry(
    @Schema(example = "3", description = "1부터 시작하는 순위. 동점은 동일 rank.")
    val rank: Int,
    @Schema(example = "usr_abc123")
    val userId: String,
    @Schema(example = "테라월더")
    val nickname: String,
    @Schema(example = "42", description = "type 에 따라: engagement=기록 수, decoration=배치 수")
    val score: Long,
    @Schema(example = "false", description = "현재 인증 사용자 본인 여부")
    val isSelf: Boolean,
)

@Schema(description = "월간 랭킹 응답")
data class RankingResponse(
    @Schema(example = "engagement", allowableValues = ["engagement", "decoration"])
    val type: String,
    @Schema(example = "2026-04", description = "YYYY-MM")
    val yearMonth: String,
    val entries: List<RankingEntry>,
    @Schema(example = "12", nullable = true, description = "현재 인증 사용자 순위. 미참여 시 null.")
    val myRank: Int?,
    @Schema(example = "18", nullable = true, description = "현재 인증 사용자 점수. 미참여 시 null.")
    val myScore: Long?,
)
