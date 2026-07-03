package com.terraworld.api.entitlement

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.domain.entitlement.EntitlementEventRepository
import com.terraworld.domain.entitlement.UserEntitlementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * UltraPlan v3 J-FREE-001 + Codex post-audit HIGH 1 (2026-05-18):
 *
 * IAP entitlement 부여/회수 + 영구 ledger.
 *
 * 사용처:
 *  - PurchaseService / PlayBillingWebhookController 의 결제 완료 hook → [grant]
 *  - PlayBillingWebhookController 의 RTDN refund event → [revoke]
 *  - PlacementController 의 자유배치 권한 체크 → [hasEntitlement]
 *
 * 본 서비스는 user_entitlement 와 entitlement_event 를 atomic 으로 갱신.
 * grant 시 row 추가 + GRANT ledger / revoke 시 row 삭제 + REVOKE ledger.
 */
@Service
class EntitlementService(
    private val userEntitlementRepository: UserEntitlementRepository,
    private val entitlementEventRepository: EntitlementEventRepository,
    private val auditService: AuditService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional(readOnly = true)
    fun hasEntitlement(
        userId: String,
        entitlementKey: String,
    ): Boolean = userEntitlementRepository.existsByIdUserIdAndIdEntitlementKey(userId, entitlementKey)

    @Transactional(readOnly = true)
    fun listEntitlements(userId: String): List<String> = userEntitlementRepository.findAllByIdUserId(userId).map { it.id.entitlementKey }

    /**
     * Entitlement 부여. 이미 있으면 no-op (idempotent — Play purchaseToken 중복 webhook 대응).
     *
     * @param reason [EntitlementEvent.REASON_PURCHASE] / SYSTEM_GRANT / ADMIN_OVERRIDE
     * @param txRef Play purchaseToken or Apple receipt ID (idempotency 키)
     */
    @Transactional
    fun grant(
        userId: String,
        entitlementKey: String,
        reason: String,
        txRef: String? = null,
    ): Boolean {
        // M3 fix (2026-07): check-then-save 대신 native ON CONFLICT DO NOTHING (UserGrantRepository 패턴).
        // 동일 IAP 의 동시 중복 도착 시 exists=false→save 경합이 DataIntegrityViolation 을 던져
        // tx 를 rollback-only 로 오염 → 커밋 시 500(UnexpectedRollbackException, 멱등 계약 위반).
        // insertIfAbsent 는 PK(user_id,entitlement_key) 또는 partial unique(tx_ref) 어느 충돌이든
        // 예외 없이 0 rows 반환한다. inserted==0 은 두 경우가 섞여 있어(bare ON CONFLICT) 구분이 필요:
        //   (a) PK 충돌 = 동일 (user,key) 이미 부여됨 → 정상 멱등 no-op.
        //   (b) tx_ref 전역 partial unique 충돌 = 같은 tx_ref 를 다른 row 가 선점 → 이 (user,key) 는
        //       미부여인데 insert 가 막힘. (a) 로 오인해 조용히 삼키면 정상 grant 가 유실될 수 있다(R2-ENT-TXREF).
        // 실제 (user,key) 존재 여부로 (a)/(b) 판정 — 존재하면 멱등, 아니면 tx_ref 재사용 이상으로 surface.
        val inserted = userEntitlementRepository.insertIfAbsent(userId, entitlementKey, txRef)
        if (inserted == 0) {
            if (userEntitlementRepository.existsByIdUserIdAndIdEntitlementKey(userId, entitlementKey)) {
                log.info("entitlement.grant.skip user={} key={} (already granted)", userId, entitlementKey)
                return false
            }
            // (b) tx_ref 재사용 — 정상 시나리오(고유 purchaseToken)에서는 도달 불가.
            // throw 금지(R3-ENT-01): grant 는 webhook 의 @Transactional(REQUIRED)에 참여 → throw 시 공유 tx 가
            //   rollback-only 로 오염돼 dedup 마커(webhook_inbox)까지 롤백 → HTTP 500 → Pub/Sub 재전송 →
            //   dedup 부재로 재처리 → 무한 redelivery storm(SEC-001 회귀). 공유 tx 를 깨지 않고 surface:
            //   audit + return false (SEC-001 의 try/catch→200 과 동일 철학).
            log.error(
                "entitlement.grant.txref-conflict user={} key={} txRef={} — tx_ref 가 다른 entitlement 에 이미 사용됨",
                userId,
                entitlementKey,
                txRef,
            )
            auditService.publish(
                userId = userId,
                action = "ENTITLEMENT_GRANT_TXREF_CONFLICT",
                resourceType = "Entitlement",
                resourceId = entitlementKey,
                payload = mapOf("reason" to reason, "txRef" to (txRef ?: "null")),
            )
            return false
        }

        entitlementEventRepository.save(
            EntitlementEvent(
                userId = userId,
                entitlementKey = entitlementKey,
                action = EntitlementEvent.ACTION_GRANT,
                reason = reason,
                txRef = txRef,
            ),
        )
        auditService.publish(
            userId = userId,
            action = "ENTITLEMENT_GRANT",
            resourceType = "Entitlement",
            payload =
                mapOf(
                    "entitlementKey" to entitlementKey,
                    "reason" to reason,
                    "txRef" to (txRef ?: "null"),
                ),
        )
        return true
    }

    /**
     * Entitlement 회수 (refund / chargeback / admin override).
     *
     * Codex audit PAY-005 fix (2026-05-18):
     *   기존: deleted == 0 시 silent return — idempotency 의도와 missed audit 구분 불가
     *   수정: row 0건 시 REVOKE ledger 에 `attempt`-marker 추가 + audit publish
     *         (실 revoke 시도 흔적 보존, 환불 webhook 중복 호출 추적)
     *
     * 1) user_entitlement row 삭제 시도
     * 2) entitlement_event REVOKE ledger 추가 (deleted 여부 무관 — audit 누락 회피)
     * 3) AuditService 영구 기록
     *
     * Soft-effect (자유배치 → 슬롯 fallback) 는 호출자가 PlacementController 통해 처리.
     */
    @Transactional
    fun revoke(
        userId: String,
        entitlementKey: String,
        reason: String,
        txRef: String? = null,
    ): Boolean {
        val deleted = userEntitlementRepository.deleteByIdUserIdAndIdEntitlementKey(userId, entitlementKey)

        // PAY-005 fix: deleted == 0 도 REVOKE ledger 에 기록 (missed audit 회피).
        // reason 끝에 `_NO_PRIOR_GRANT` suffix 로 idempotent revoke 구분.
        val effectiveReason = if (deleted == 0L) "${reason}_NO_PRIOR_GRANT" else reason
        entitlementEventRepository.save(
            EntitlementEvent(
                userId = userId,
                entitlementKey = entitlementKey,
                action = EntitlementEvent.ACTION_REVOKE,
                reason = effectiveReason,
                txRef = txRef,
            ),
        )
        auditService.publish(
            userId = userId,
            action = "ENTITLEMENT_REVOKE",
            resourceType = "Entitlement",
            payload =
                mapOf(
                    "entitlementKey" to entitlementKey,
                    "reason" to effectiveReason,
                    "txRef" to (txRef ?: "null"),
                    "rowDeleted" to (deleted > 0L),
                ),
        )
        if (deleted == 0L) {
            log.info("entitlement.revoke.no-prior-grant user={} key={} reason={}", userId, entitlementKey, reason)
            return false
        }
        log.warn("entitlement.revoke user={} key={} reason={}", userId, entitlementKey, reason)
        return true
    }
}
