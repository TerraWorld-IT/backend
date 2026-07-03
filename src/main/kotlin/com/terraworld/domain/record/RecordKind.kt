package com.terraworld.domain.record

/**
 * 기록 종류 (낙서장 P1). 습관 = 7일 트래커(반짝이) / 일상 = 사진·일기·집중·거리(토큰+코인).
 */
enum class RecordKind { HABIT, DAILY }

/**
 * 일상 기록 하위 타입 → 활동 토큰 매핑 (낙서장 §B).
 * PHOTO→DEW · DIARY→SUN · FOCUS→BOLT · DISTANCE→WIND.
 */
enum class DailyType(
    val currencyCode: String,
    val coinReward: Long,
    val tokenReward: Long,
) {
    PHOTO("DEW", 10, 2),
    DIARY("SUN", 12, 2),
    FOCUS("BOLT", 15, 3),
    DISTANCE("WIND", 15, 3),
}
