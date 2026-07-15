package com.terraworld.common.audit

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.domain.audit.AuditLog
import com.terraworld.domain.audit.AuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * 비즈니스 트랜잭션이 commit 된 직후 audit 로그 row 를 INSERT 한다.
 *
 * 별도 트랜잭션(`Propagation.REQUIRES_NEW`)으로 동작하므로 audit 실패가
 * 비즈니스 결과를 되돌리지 않는다. 모든 예외는 캡처해 WARN 로그로 남김
 * (best-effort, eventual).
 *
 * BE-17 (2026-07-15 성능 감사): `@Async("auditExecutor")` — AFTER_COMMIT 리스너를 동기로 두면
 * 요청 스레드가 커밋 후 audit INSERT(REQUIRES_NEW 커넥션 획득 포함)까지 대기한다. audit 는
 * best-effort 계약이므로 전용 executor([AuditAsyncConfig])로 분리해 요청 latency 에서 제거.
 * REQUIRES_NEW 는 async 스레드에서 새 tx 를 열기 위해 유지.
 *
 * 운영 신호 (SEC-002): 실패 시 `event=audit_log_dropped` 태그가 붙은 WARN 로그를
 * 발행한다. 모니터링은 이 태그로 카운터를 만들고 `> 0/min` 알림 권장.
 *
 * Payload 보호 (SEC-007): JSON 직렬화 결과는 `MAX_PAYLOAD_BYTES` 로 truncate
 * 하여 audit 테이블이 무한 증식하지 않게 한다.
 */
@Component
class AuditEventListener(
    private val repository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AuditEventListener::class.java)

    companion object {
        /** SEC-007: payload 길이 상한 (UTF-16 chars). 8KB 면 일반 도메인 이벤트는 모두 수용. */
        const val MAX_PAYLOAD_CHARS: Int = 8192
        const val TRUNCATION_MARKER: String = "…[truncated]"
    }

    // BE-01 (2026-07-15 성능 감사): fallbackExecution=true — 결제 컨트롤러(IapVerifyController /
    // PlayBillingWebhookController)의 @Transactional 제거로 tx 밖 auditService.publish 가 발생한다
    // (webhook unauthorized / IAP user-mismatch / BILLING_IAP_VERIFIED 등). fallback 이 없으면
    // @TransactionalEventListener 는 활성 tx 부재 시 event 를 **조용히 drop** 한다 (audit 유실).
    // tx 안에서 publish 된 event 는 기존대로 AFTER_COMMIT 에만 실행 (rollback 시 미기록 유지).
    @Async("auditExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handle(event: AuditEvent) {
        try {
            val payloadJson =
                event.payload?.let {
                    try {
                        truncate(objectMapper.writeValueAsString(it))
                    } catch (e: JsonProcessingException) {
                        log.warn(
                            "[audit] event=audit_log_dropped reason=serialization_failed action={} cause={}",
                            event.action,
                            e.message,
                        )
                        null
                    }
                }
            repository.save(
                AuditLog(
                    userId = event.userId,
                    action = event.action,
                    resourceType = event.resourceType,
                    resourceId = event.resourceId,
                    payload = payloadJson,
                ),
            )
        } catch (e: DataAccessException) {
            log.warn(
                "[audit] event=audit_log_dropped reason=db_write_failed action={} userId={} cause={}",
                event.action,
                event.userId,
                e.message,
            )
        }
    }

    private fun truncate(json: String): String =
        if (json.length <= MAX_PAYLOAD_CHARS) {
            json
        } else {
            json.take(MAX_PAYLOAD_CHARS - TRUNCATION_MARKER.length) + TRUNCATION_MARKER
        }
}
