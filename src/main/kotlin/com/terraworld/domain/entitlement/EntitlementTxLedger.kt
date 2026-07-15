package com.terraworld.domain.entitlement

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

/**
 * V36 (Codex 리뷰 백로그, 2026-07-15): tx_ref(purchaseToken) 처리 원장 — append-only.
 *
 * [UserEntitlement] 의 tx_ref unique(uq_user_entitlement_tx_ref)는 **현재 상태** row 에만
 * 존재한다 — revoke 가 row 를 삭제하면 이전 grant webhook 재전송(특히 notificationId 없는
 * 경로 — inbox dedup 미적용)이 같은 tx_ref 로 다시 insert 되어 취소·환불된 권리가 복원된다.
 *
 * 본 원장은 tx_ref 별 처리 사실을 영구 기록: UNIQUE (tx_ref, action) — 한 purchaseToken 은
 * GRANT 1회 + REVOKE 1회만 처리 가능. row 존재 여부와 무관하게 grant 재전송을 멱등 차단.
 *
 * [EntitlementEvent] 와의 차이: event 는 사후 감사용 자유 이력(동일 tx_ref 복수 기록 가능),
 * 본 원장은 멱등 판정용 유일 기록(insert-if-absent 의 conflict 가 곧 "이미 처리됨" 신호).
 *
 * append-only — row 수정 / 삭제 금지 (운영 정책). users FK 없음: 탈퇴 후에도 멱등 기록 유지.
 */
@Entity
@Table(name = "entitlement_tx_ledger")
class EntitlementTxLedger(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false, length = 255)
    val userId: String,
    @Column(name = "entitlement_key", nullable = false, length = 64)
    val entitlementKey: String,
    @Column(name = "tx_ref", nullable = false, length = 128)
    val txRef: String,
    @Column(nullable = false, length = 16)
    val action: String, // GRANT 또는 REVOKE
    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        const val ACTION_GRANT = "GRANT"
        const val ACTION_REVOKE = "REVOKE"
    }
}
