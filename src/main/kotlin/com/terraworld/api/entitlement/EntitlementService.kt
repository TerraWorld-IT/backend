package com.terraworld.api.entitlement

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.domain.entitlement.EntitlementEventRepository
import com.terraworld.domain.entitlement.UserEntitlementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [EntitlementService.grant] 결과 3분류 (R4-ENT-01, 2026-07-15 적대적 리뷰).
 *
 * 기존 Boolean 반환은 "이미 보유(정상 멱등)" 와 "tx_ref 충돌(실제 실패)" 를 모두 false 로
 * 뭉갰다 — 후자가 webhook inbox 와 함께 커밋되어 200 응답 → 재전송이 duplicate 로 차단
 * → 결제가 영구 유실되는 손실형 sentinel 이었다.
 */
enum class GrantResult {
    /** 신규 부여 — row + GRANT ledger 기록됨. */
    GRANTED,

    /** 동일 (user, entitlementKey) 이미 보유 — 정상 멱등 no-op (기존 granted=false 계약). */
    ALREADY_PRESENT,

    /**
     * tx_ref(purchaseToken) 가 **다른 row** 에 이미 사용됨 — 이 (user, key) 는 미부여인데
     * insert 가 막힌 실제 실패. 호출부는 반드시 [EntitlementTxRefConflictException] 등으로
     * escalate 해 트랜잭션(webhook inbox 포함)을 롤백시키고 5xx 를 반환해야 한다.
     */
    TX_REF_CONFLICT,
}

/**
 * [GrantResult.TX_REF_CONFLICT] escalate 용. GlobalExceptionHandler 에 미매핑 → HTTP 500.
 *
 * webhook 경로: BillingWebhookTxService 의 tx 를 rollback-only 로 만들어 inbox dedup 마커도
 * 함께 롤백 → 5xx → Pub/Sub/클라이언트 재전송이 duplicate-skip 으로 은폐되지 않고 계속
 * surface 된다 (동일 conflict 가 지속되면 재전송 반복 → dead-letter/운영 알람 — grant 가
 * 성공한 적이 없으므로 부수효과 중복은 없다. 조용한 결제 유실보다 낮은 리스크).
 */
class EntitlementTxRefConflictException(
    message: String,
) : IllegalStateException(message)

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
     * R4-ENT-01 (2026-07-15): 반환을 [GrantResult] 3분류로. [GrantResult.TX_REF_CONFLICT] 는
     * 정상 멱등이 아닌 실제 실패 — 호출부(IapVerifyController / PlayBillingWebhookController)가
     * [EntitlementTxRefConflictException] 으로 escalate 해 inbox 포함 tx 를 롤백하고 5xx 를
     * 반환한다. 본 메서드가 직접 throw 하지 않는 이유: IAP 경로에서는 conflict audit 이벤트를
     * (본 메서드의 tx 커밋과 함께) 보존하기 위해 — throw 는 호출부 책임으로 위임.
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
    ): GrantResult {
        // M3 fix (2026-07): check-then-save 대신 native ON CONFLICT DO NOTHING (UserGrantRepository 패턴).
        // 동일 IAP 의 동시 중복 도착 시 exists=false→save 경합이 DataIntegrityViolation 을 던져
        // tx 를 rollback-only 로 오염 → 커밋 시 500(UnexpectedRollbackException, 멱등 계약 위반).
        // insertIfAbsent 는 PK(user_id,entitlement_key) 또는 partial unique(tx_ref) 어느 충돌이든
        // 예외 없이 0 rows 반환한다. inserted==0 은 두 경우가 섞여 있어(bare ON CONFLICT) 구분이 필요:
        //   (a) PK 충돌 = 동일 (user,key) 이미 부여됨 → 정상 멱등 no-op → ALREADY_PRESENT.
        //   (b) tx_ref 전역 partial unique(uq_user_entitlement_tx_ref) 충돌 = 같은 tx_ref 를 다른
        //       row 가 선점 → 이 (user,key) 는 미부여인데 insert 가 막힘 → TX_REF_CONFLICT.
        // 실제 (user,key) 존재 여부 후속 SELECT 로 (a)/(b) 판정. (b) 를 (a) 로 오인해 조용히
        // 삼키면(구 Boolean false) webhook 200 → 재전송 duplicate 차단 → 결제 영구 유실(R2-ENT-TXREF).
        val inserted = userEntitlementRepository.insertIfAbsent(userId, entitlementKey, txRef)
        if (inserted == 0) {
            if (userEntitlementRepository.existsByIdUserIdAndIdEntitlementKey(userId, entitlementKey)) {
                log.info("entitlement.grant.skip user={} key={} (already granted)", userId, entitlementKey)
                return GrantResult.ALREADY_PRESENT
            }
            // (b) tx_ref 재사용 — 정상 시나리오(고유 purchaseToken)에서는 도달 불가.
            // R4-ENT-01: 과거(R3-ENT-01)에는 redelivery storm 우려로 삼켰으나(return false),
            // 그 결과가 "결제 유실 은폐" 였다. conflict 가 지속돼도 grant 는 성공한 적이 없어
            // 재전송의 부수효과 중복은 없고, 반복 5xx 는 dead-letter/운영 알람으로 surface 된다.
            // audit 은 webhook 경로에서는 호출부 throw 로 tx 가 롤백되며 유실될 수 있다 —
            // log.error 가 최소 보장 (IAP 경로는 본 tx 커밋과 함께 audit 보존).
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
            return GrantResult.TX_REF_CONFLICT
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
        return GrantResult.GRANTED
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
