package com.terraworld

import com.terraworld.common.time.JvmTimeZone
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

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
    // 무인자 now() 를 UTC 로 간주하는 저장·응답 계약을 환경과 무관하게 지킨다 (JvmTimeZone 참조).
    JvmTimeZone.pinUtc()
    runApplication<TerraWorldApplication>(*args)
}
