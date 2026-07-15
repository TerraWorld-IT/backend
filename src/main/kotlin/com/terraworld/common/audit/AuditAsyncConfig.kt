package com.terraworld.common.audit

import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.RejectedExecutionHandler

/**
 * BE-17 (2026-07-15 성능 감사): AuditEventListener 전용 ThreadPoolTaskExecutor.
 *
 * AFTER_COMMIT 리스너가 동기 + REQUIRES_NEW 로 돌면 요청 스레드가 커밋 직후 audit INSERT
 * 왕복(새 커넥션 획득 포함)까지 기다린다 — audit 는 best-effort 계약(실패해도 WARN 만)이므로
 * 요청 latency 에서 분리. FcmAsyncConfig 패턴 준용 (전용 executor — FCM burst 와 격리).
 *
 * - corePoolSize 2 + maxPoolSize 4 + queue 500 — audit 는 단건 INSERT 라 소폭 pool 로 충분
 * - CallerRunsPolicy — 포화 시 caller 가 직접 실행 (audit 유실 대신 backpressure)
 * - WaitForTasksToCompleteOnShutdown — shutdown 시 대기 중 audit flush (best-effort)
 */
@Configuration
@EnableAsync
class AuditAsyncConfig {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Bean 이름 "auditExecutor" — AuditEventListener 가 @Async("auditExecutor") 로 명시 사용. */
    @Bean(name = ["auditExecutor"])
    fun auditExecutor(): ThreadPoolTaskExecutor {
        val executor = ThreadPoolTaskExecutor()
        executor.corePoolSize = 2
        executor.maxPoolSize = 4
        executor.queueCapacity = 500
        executor.setThreadNamePrefix("audit-async-")
        executor.setKeepAliveSeconds(60)
        executor.setWaitForTasksToCompleteOnShutdown(true)
        executor.setAwaitTerminationSeconds(15)
        executor.setRejectedExecutionHandler(
            RejectedExecutionHandler { runnable, _ ->
                log.warn("audit.executor.queue-full — caller thread 가 직접 실행 (burst 감지)")
                runnable.run()
            },
        )
        executor.initialize()
        return executor
    }
}
