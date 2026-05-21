package com.terraworld.domain.entitlement

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.time.LocalDateTime

/**
 * UltraPlan v3 J-FREE-001 (2026-05-18, V13):
 *
 * 현재 활성 entitlement. IAP 결제 시 row 추가, 환불 시 row 삭제 + entitlement_event ledger 추가.
 *
 * Key naming convention (snake_case):
 *  - free_placement: 자유배치 해금 (1회 구매, 영구)
 *  - season_pass_YYYYqQ: 시즌 패스 (월 구독)
 *  - theme_pack_YYYY: 한정 테마팩
 *
 * Codex post-audit HIGH 1 — refund/revoke 시 본 row 삭제 + entitlement_event REVOKE 추가.
 */
@Entity
@Table(name = "user_entitlement")
class UserEntitlement(
    @EmbeddedId
    val id: UserEntitlementId,
    @Column(name = "granted_at", nullable = false, updatable = false)
    val grantedAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "tx_ref", length = 128)
    val txRef: String? = null,
)

@Embeddable
data class UserEntitlementId(
    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String = "",
    @Column(name = "entitlement_key", nullable = false, length = 64)
    val entitlementKey: String = "",
) : Serializable {
    companion object {
        // 표준 entitlement key
        const val FREE_PLACEMENT = "free_placement"
        const val PREMIUM_THEMES = "premium_themes"
    }
}
