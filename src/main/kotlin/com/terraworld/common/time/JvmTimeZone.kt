package com.terraworld.common.time

import java.util.TimeZone

/**
 * JVM 기본 시간대 고정.
 *
 * 저장·응답 시각은 `LocalDateTime.now()` 를 UTC 로 간주해 내보낸다(`RecordService` 의 `.atOffset(ZoneOffset.UTC)` 등
 * 무인자 now() 58곳). 운영 컨테이너(eclipse-temurin alpine, deploy compose 에 TZ 미설정)는 UTC 라 문제가 없지만
 * 로컬 개발 PC 처럼 기본 시간대가 KST 인 환경에서는 같은 값이 +9h 밀린다. 부팅 첫 줄에서 UTC 로 고정해
 * 환경 차이를 없앤다. 한국 날짜 경계(출석·습관·키우기·시들기)는 [KstTime] 이 Asia/Seoul 을 명시하므로 영향 없다.
 */
object JvmTimeZone {
    val UTC: TimeZone = TimeZone.getTimeZone("UTC")

    /** 기본 시간대를 UTC 로 바꾸고 이전 값을 돌려준다(테스트 복원용). */
    fun pinUtc(): TimeZone {
        val previous = TimeZone.getDefault()
        TimeZone.setDefault(UTC)
        return previous
    }
}
