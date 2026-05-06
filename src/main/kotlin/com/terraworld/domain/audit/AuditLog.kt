package com.terraworld.domain.audit

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.OffsetDateTime

/**
 * 잔고/보상/구매/구조 변경 이벤트의 사후 추적 레코드.
 *
 * 작성 시점: `@TransactionalEventListener(AFTER_COMMIT)`. 비즈니스 트랜잭션
 * 이 성공적으로 커밋된 뒤에만 기록되므로 audit 누락은 있을 수 있어도 잘못된
 * 기록은 발생하지 않는다 (best-effort, eventual).
 */
@Entity
@Table(name = "audit_logs")
class AuditLog(
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "action", nullable = false, length = 64)
    val action: String,
    @Column(name = "resource_type", length = 64)
    val resourceType: String? = null,
    @Column(name = "resource_id", length = 128)
    val resourceId: String? = null,
    /**
     * JSON-encoded payload. publisher 가 Jackson 으로 직렬화한 문자열을
     * 그대로 저장한다. PII 가 들어가지 않게 publisher 측에서 책임진다.
     * Hibernate 6 의 `JdbcTypeCode(SqlTypes.JSON)` 으로 PostgreSQL `jsonb` 매핑.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb")
    val payload: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    var id: Long = 0
}
