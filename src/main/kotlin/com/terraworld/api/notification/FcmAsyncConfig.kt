package com.terraworld.api.notification

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.RejectedExecutionHandler

/**
 * UltraPlan v3 — Codex audit FCM-001 fix (2026-05-18, MEDIUM):
 *
 * FCM EventListener 전용 ThreadPoolTaskExecutor.
 * 기본 SimpleAsyncTaskExecutor 는 매 호출마다 thread 생성 — burst 시 OOM 위험.
 *
 * 권장 default (§4.2.A 자율 진행):
 *  - corePoolSize 4 + maxPoolSize 16 + queue 200 — 일반 wilting/attendance/friend 처리량 cover
 *  - CallerRunsPolicy — queue full + maxPool 도달 시 caller thread 가 직접 실행 (FCM 발송 차단 회피)
 *  - WaitForTasksToCompleteOnShutdown — graceful shutdown
 *
 * @EnableAsync 가 application-wide 가 아닌 본 Config 에 한정 — 다른 @Async 사용처와 분리.
 * 다른 도메인 (AuditService 등) 이 본 executor 공유 시 별 Config 분리 권장.
 */
@Configuration
@EnableAsync
class FcmAsyncConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Bean 이름 "fcmExecutor" — FcmEventListener 가 @Async("fcmExecutor") 로 명시 사용.
     * 다른 @Async 호출은 default Spring executor 사용 (격리).
     */
    @Bean(name = ["fcmExecutor"])
    fun fcmExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 4
        executor.maxPoolSize = 16
        executor.queueCapacity = 200
        executor.setThreadNamePrefix("fcm-async-")
        executor.setKeepAliveSeconds(60)
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(30)
        // CallerRunsPolicy — queue full + maxPool 도달 시 caller thread 가 직접 실행.
        // FCM 발송 차단 회피 (다른 transaction blocking 위험 낮음 — listener 는 @EventListener async).
        executor.setRejectedExecutionHandler(
            RejectedExecutionHandler { runnable, _ ->
                log.warn("fcm.executor.queue-full — caller thread 가 직접 실행 (burst 감지)")
                runnable.run()
            },
        )
        executor.initialize()
        return executor
    }
}
