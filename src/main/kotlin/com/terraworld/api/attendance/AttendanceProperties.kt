package com.terraworld.api.attendance

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 출석 보상표 설정 (아프젝 v2 §R4). 종전 BASE_REWARD/BONUS_REWARD 상수를 대체한다.
 *
 * - `attendance.board.coins`: 7일 사이클 일차별 기본 코인 (day 1~7). 정확히 7개여야 한다.
 * - `attendance.cycle-bonus-ruby`: 7일차 체크인 시 추가 지급 루비.
 */
@ConfigurationProperties(prefix = "attendance")
data class AttendanceProperties(
    val board: Board = Board(),
    val cycleBonusRuby: Int = 5,
) {
    data class Board(
        val coins: List<Int> = listOf(10, 20, 30, 40, 50, 60, 70),
    )

    init {
        require(board.coins.size == CYCLE_DAYS) { "attendance.board.coins 는 정확히 ${CYCLE_DAYS}개여야 합니다 (현재 ${board.coins.size}개)" }
        require(board.coins.all { it >= 0 }) { "attendance.board.coins 는 음수 불가" }
        require(cycleBonusRuby >= 0) { "attendance.cycle-bonus-ruby 는 음수 불가" }
    }

    /** 사이클 일차(1~7)의 기본 코인. */
    fun coinsForDay(cycleDay: Int): Int = board.coins[cycleDay - 1]

    companion object {
        const val CYCLE_DAYS = 7
    }
}
