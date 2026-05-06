package com.terraworld.domain.audit

import org.springframework.data.jpa.repository.JpaRepository

interface AuditLogRepository : JpaRepository<AuditLog, Long> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: String): List<AuditLog>

    fun findAllByActionOrderByCreatedAtDesc(action: String): List<AuditLog>
}
