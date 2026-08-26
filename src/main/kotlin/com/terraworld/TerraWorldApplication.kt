package com.terraworld

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling
import java.util.TimeZone

@SpringBootApplication(
    // We own authentication via JwtAuthenticationFilter + better-auth JWKS.
    // Exclude the default in-memory user autoconfig that would otherwise
    // spam "Using generated security password: ..." on every boot.
    exclude = [UserDetailsServiceAutoConfiguration::class],
)
@ConfigurationPropertiesScan
// N14 (구현 계획서 v4): WiltScheduler 의 @Scheduled cron 활성화.
@EnableScheduling
class TerraWorldApplication

fun main(args: Array<String>) {
    // 저장·응답 시각은 LocalDateTime.now() 를 UTC 로 간주해 내려간다(RecordService.atOffset(UTC) 등).
    // JVM 기본 시간대가 KST 인 환경(로컬 개발 PC)에서는 그 값이 +9h 밀리므로 운영 컨테이너와 같은 UTC 로 고정한다.
    // 한국 날짜 경계(출석·습관·키우기)는 각자 Asia/Seoul 을 명시해 쓰므로 영향 없다.
    TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
    runApplication<TerraWorldApplication>(*args)
}
