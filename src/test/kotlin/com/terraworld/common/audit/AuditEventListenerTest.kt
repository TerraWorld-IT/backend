package com.terraworld.common.audit

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.domain.audit.AuditLog
import com.terraworld.domain.audit.AuditLogRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException

class AuditEventListenerTest {
    private val repository: AuditLogRepository = mock()
    private val objectMapper = ObjectMapper()
    private val listener = AuditEventListener(repository, objectMapper)

    @Test
    fun `payload 가 JSON 으로 직렬화되어 INSERT 된다`() {
        val event =
            AuditEvent(
                userId = "user-42",
                action = "EXCHANGE_S2B",
                resourceType = "Wallet",
                resourceId = null,
                payload = mapOf("specialCoinSpent" to 10, "basicCoinReceived" to 20, "rate" to 2.0),
            )
        whenever(repository.save(org.mockito.kotlin.any<AuditLog>())).thenAnswer { it.arguments[0] as AuditLog }

        listener.handle(event)

        val captor = argumentCaptor<AuditLog>()
        verify(repository).save(captor.capture())
        val saved = captor.firstValue
        assertThat(saved.userId).isEqualTo("user-42")
        assertThat(saved.action).isEqualTo("EXCHANGE_S2B")
        assertThat(saved.resourceType).isEqualTo("Wallet")
        assertThat(saved.payload).contains("\"specialCoinSpent\":10")
        assertThat(saved.payload).contains("\"basicCoinReceived\":20")
    }

    @Test
    fun `payload 가 null 이면 컬럼도 null`() {
        val event = AuditEvent(userId = "user-1", action = "ATTENDANCE_CHECKIN", resourceType = "Attendance")
        whenever(repository.save(org.mockito.kotlin.any<AuditLog>())).thenAnswer { it.arguments[0] as AuditLog }

        listener.handle(event)

        val captor = argumentCaptor<AuditLog>()
        verify(repository).save(captor.capture())
        assertThat(captor.firstValue.payload).isNull()
    }

    @Test
    fun `repository 장애는 swallow 되어 비즈니스 트랜잭션을 깨지 않는다`() {
        whenever(repository.save(org.mockito.kotlin.any<AuditLog>())).thenThrow(DataAccessResourceFailureException("db down"))
        val event = AuditEvent(userId = "user-1", action = "REWARD_AD_CLAIMED", resourceType = "Reward")

        // 예외가 그대로 나가지 않으면 OK (테스트 본문 자체가 통과한다는 사실).
        listener.handle(event)

        verify(repository).save(org.mockito.kotlin.any<AuditLog>())
    }

    @Test
    fun `직렬화 불가 payload 는 null 로 저장되며 INSERT 는 진행된다`() {
        // ObjectMapper 가 던질 만한 매핑은 거의 없지만 (Map<String, Any?> 는 거의 다 직렬화 가능),
        // 직접 Mockito 로 ObjectMapper 를 mock 하여 시뮬레이션.
        val mapper: ObjectMapper = mock()
        whenever(mapper.writeValueAsString(org.mockito.kotlin.any())).thenThrow(
            com.fasterxml.jackson.core
                .JsonGenerationException("simulated", null as com.fasterxml.jackson.core.JsonGenerator?),
        )
        val customListener = AuditEventListener(repository, mapper)

        whenever(repository.save(org.mockito.kotlin.any<AuditLog>())).thenAnswer { it.arguments[0] as AuditLog }
        val event = AuditEvent(userId = "user-1", action = "PURCHASE_ITEM", payload = mapOf("k" to "v"))

        customListener.handle(event)

        val captor = argumentCaptor<AuditLog>()
        verify(repository).save(captor.capture())
        assertThat(captor.firstValue.payload).isNull()
    }
}

class AuditServiceTest {
    @Test
    fun `publish 는 이벤트를 ApplicationEventPublisher 로 위임한다`() {
        val publisher: org.springframework.context.ApplicationEventPublisher = mock()
        val service = AuditService(publisher)

        service.publish(
            userId = "user-1",
            action = "EXCHANGE_TOKEN",
            resourceType = "Wallet",
            payload = mapOf("rate" to 2.0),
        )

        val captor = argumentCaptor<AuditEvent>()
        verify(publisher).publishEvent(captor.capture())
        assertThat(captor.firstValue.userId).isEqualTo("user-1")
        assertThat(captor.firstValue.action).isEqualTo("EXCHANGE_TOKEN")
        assertThat(captor.firstValue.payload).containsEntry("rate", 2.0)
    }
}
