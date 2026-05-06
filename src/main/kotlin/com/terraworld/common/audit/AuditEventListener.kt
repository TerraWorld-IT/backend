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
 */
@Component
class AuditEventListener(
    private val repository: AuditLogRepository,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(AuditEventListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun handle(event: AuditEvent) {
        try {
            val payloadJson =
                event.payload?.let {
                    try {
                        objectMapper.writeValueAsString(it)
                    } catch (e: JsonProcessingException) {
                        log.warn("[audit] payload serialization failed for action={}: {}", event.action, e.message)
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
            log.warn("[audit] DB write failed for action={} userId={}: {}", event.action, event.userId, e.message)
        }
    }
}
