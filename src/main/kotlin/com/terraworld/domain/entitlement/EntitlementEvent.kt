package com.terraworld.domain.entitlement

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * UltraPlan v3 J-FREE-001 (V13, Codex post-audit HIGH 1):
 *
 * entitlement 부여/회수 영구 ledger. user_entitlement 의 history 보존.
 * refund 후 chargeback / 재구매 시 audit 가능.
 *
 * 본 테이블은 user_entitlement 와 달리 **read-mostly + append-only**.
 * row 수정 / 삭제 금지 (운영 정책).
 */
@Entity
@Table(name = "entitlement_event")
class EntitlementEvent(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String,
    @Column(name = "entitlement_key", nullable = false, length = 64)
    val entitlementKey: String,
    @Column(nullable = false, length = 16)
    val action: String, // GRANT | REVOKE
    @Column(nullable = false, length = 32)
    val reason: String, // PURCHASE | REFUND | CHARGEBACK | ADMIN_OVERRIDE | SYSTEM_GRANT
    @Column(name = "tx_ref", length = 128)
    val txRef: String? = null,
    @Column(name = "occurred_at", nullable = false, updatable = false)
    val occurredAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val ACTION_GRANT = "GRANT"
        const val ACTION_REVOKE = "REVOKE"
        const val REASON_PURCHASE = "PURCHASE"
        const val REASON_REFUND = "REFUND"
        const val REASON_CHARGEBACK = "CHARGEBACK"
        const val REASON_ADMIN_OVERRIDE = "ADMIN_OVERRIDE"
        const val REASON_SYSTEM_GRANT = "SYSTEM_GRANT"
    }
}
