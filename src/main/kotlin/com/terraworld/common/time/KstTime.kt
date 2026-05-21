package com.terraworld.common.time

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * KST (Asia/Seoul) timezone helper.
 *
 * /analyze 2026-05-18 (Codex C-5): backend 10 사이트가 `LocalDate.now()` 를 JVM default
 * timezone 으로 호출 — Docker base `eclipse-temurin:21-jre-alpine` 기본 TZ 가 UTC 라
 * KST 자정 ±9h drift 발생 가능. frontend `useTimeAwareColorMode.ts` 는 KST 9 고정.
 *
 * 본 helper 는 단일 진실 (KST_ZONE) + `today()` / `now()` 한 줄 호출로 일괄 치환.
 * Clock Bean 주입 대비 가벼움 — test 격리는 추후 필요 시 Bean 으로 격상.
 *
 * 비즈니스 규칙 daily boundary 는 모두 KST 기준이어야 함:
 *  - AttendanceService.checkIn (출석 1일 1회)
 *  - RewardService.claimAdReward (광고 5회/일)
 *  - RecordService.createRecord (daily_limit)
 *  - TerrariumService.computeWiltingState (시들기 days 차)
 */
object KstTime {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    fun today(): LocalDate = LocalDate.now(ZONE)

    fun now(): LocalDateTime = LocalDateTime.now(ZONE)
}
