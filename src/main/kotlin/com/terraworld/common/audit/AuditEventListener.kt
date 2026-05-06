package com.terraworld.common.audit

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.domain.audit.AuditLog
import com.terraworld.domain.audit.AuditLogRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
