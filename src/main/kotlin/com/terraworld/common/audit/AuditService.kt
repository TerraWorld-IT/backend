package com.terraworld.common.audit

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service

/**
 * 도메인 서비스가 사용하는 publisher 진입점.
 *
 * Spring `ApplicationEventPublisher` 를 한 번 감싸서 (1) 호출 사이트가 더 짧고
 * (2) 미래에 sampling / per-action filter 를 추가할 자리를 만든다.
 */
@Service
class AuditService(
    private val applicationEventPublisher: ApplicationEventPublisher,
) {
    fun publish(event: AuditEvent) {
        applicationEventPublisher.publishEvent(event)
    }

    fun publish(
        userId: String,
        action: String,
        resourceType: String? = null,
        resourceId: String? = null,
        payload: Map<String, Any?>? = null,
    ) {
        publish(AuditEvent(userId, action, resourceType, resourceId, payload))
    }
}
