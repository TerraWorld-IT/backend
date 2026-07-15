package com.terraworld.api.entitlement

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.domain.entitlement.EntitlementEventRepository
import com.terraworld.domain.entitlement.EntitlementTxLedger
import com.terraworld.domain.entitlement.EntitlementTxLedgerRepository
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.entitlement.UserEntitlementRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * [EntitlementService.grant] 결과 분류 (V36 재설계, 2026-07-15 적대적 리뷰 2라운드).
 *
 * tx_ref(purchaseToken)별 terminal-state 상태머신의 관측 결과:
 *  - 정상 멱등([ALREADY_PRESENT] / [REVOKED])은 값으로 반환 — 호출부는 200 ACK / granted=false.
 *  - 실제 실패(tx_ref 충돌)는 값이 아니라 [EntitlementTxRefConflictException] **throw** —
 *    본 서비스의 @Transactional 안에서 던져 entitlement/원장 기록이 함께 롤백된다.
 *    (이전 라운드의 "TX_REF_CONFLICT 반환 + 호출부 throw" 설계는 잘못 선점된 GRANT 원장이
 *    서비스 tx 커밋에 실려나가는 결함이 있었다.)
 */
enum class GrantResult {
    /** 신규 부여 — row + GRANT 원장 기록됨. */
    GRANTED,

    /** 동일 (user, entitlementKey) 이미 보유 — 정상 멱등 no-op (기존 granted=false 계약). */
    ALREADY_PRESENT,

    /**
     * 이 tx_ref 는 이미 REVOKE 처리된 terminal 상태 — grant 하지 않고 멱등 반환.
     * 환불/취소된 토큰의 grant 재전송(순서 역전 포함)이 권리를 (재)생성하지 못하게 차단.
     * 호출부 계약: webhook 은 200 ACK(재전송 불필요 — 결과 불변), IAP 는 granted=false.
     */
    REVOKED,
}

/**
 * tx_ref 충돌(타 (user,key) 가 선점한 토큰의 replay) escalate 용.
 * GlobalExceptionHandler 가 HTTP 500 으로 명시 매핑 (미매핑 시 Security /error 게이트에서
 * 401 로 관측돼 인증 실패로 오독됨 — bootRun 스모크 실측으로 확인 후 매핑 추가).
 *
 * V36 재설계: [EntitlementService.grant] 가 자신의 @Transactional **안에서** 직접 throw —
 * entitlement insert / 원장 기록이 함께 롤백된다. webhook 경로에서는 상위
 * BillingWebhookTxService tx 에 참여 중이므로 inbox dedup 마커도 함께 롤백 → 5xx →
 * 재전송이 duplicate-skip 으로 은폐되지 않고 계속 surface 된다 (조용한 결제 유실 방지).
 *
 * conflict audit 은 tx 롤백과 함께 유실되므로(AFTER_COMMIT 미도달) 호출부(컨트롤러)가
 * tx **밖에서** 본 예외의 필드로 publish 한다 — AuditEventListener 의 fallbackExecution=true
 * 가 tx 부재 publish 를 수신한다. 서비스 안에서는 log.error 가 tx 무관 최소 보장.
 */
class EntitlementTxRefConflictException(
    val userId: String,
    val entitlementKey: String,
    val txRef: String?,
    val reason: String,
) : IllegalStateException(
        "entitlement grant tx_ref conflict — userId=$userId key=$entitlementKey (tx_ref 가 다른 entitlement 에 이미 사용됨)",
    )

/**
 * UltraPlan v3 J-FREE-001 + V36 tx_ref terminal-state 상태머신 (2026-07-15 재설계):
 *
 * IAP entitlement 부여/회수. tx_ref(purchaseToken)별 상태는 append-only 처리 원장
 * ([EntitlementTxLedger], UNIQUE (tx_ref, action)) 과 현재 상태 테이블(user_entitlement)로
 * 결정되며, 전이는 다음 상태머신을 따른다:
 *
 * ```
 * 상태(토큰 tx_ref 기준):
 *   UNSEEN   — 원장 기록 없음 (미처리 토큰)
 *   GRANTED  — (tx_ref, GRANT) 원장 존재 + 그 (user,key) 가 이 토큰으로 권리 보유
 *   REVOKED  — (tx_ref, REVOKE) 원장 존재 — **terminal** (어떤 전이도 불가)
 *
 * 전이:
 *   UNSEEN  --grant--> GRANTED           row insert 성공 시에만 (tx_ref, GRANT) 선점
 *   UNSEEN  --grant--> UNSEEN            같은 (user,key) 가 다른 토큰으로 이미 보유
 *                                        → ALREADY_PRESENT, **원장 미기록**(토큰 미선점 유지)
 *   UNSEEN  --grant--> (rollback)        타 (user,key) live row 가 토큰 보유 → conflict throw
 *   UNSEEN  --revoke--> REVOKED          revoke 선도착(순서 역전) — 삭제할 row 없어도 terminal 확정
 *   GRANTED --grant(같은 소유)--> GRANTED 재전송 멱등 (ALREADY_PRESENT)
 *   GRANTED --grant(타 소유)--> (rollback) replay — conflict throw
 *   GRANTED --revoke--> REVOKED          (tx_ref, REVOKE) 선점 후 tx_ref 일치 row 만 조건부 삭제
 *   REVOKED --grant--> REVOKED           멱등 반환 (GrantResult.REVOKED) — 권리 미생성
 *   REVOKED --revoke--> REVOKED          중복 REVOKE — **어떤 row 도 삭제하지 않음** (재구매 보호)
 * ```
 *
 * 사용처:
 *  - IapVerifyController / PlayBillingWebhookController 의 결제 완료 → [grant]
 *  - PlayBillingWebhookController 의 RTDN refund event → [revoke]
 *  - PlacementController 의 자유배치 권한 체크 → [hasEntitlement]
 */
@Service
class EntitlementService(
    private val userEntitlementRepository: UserEntitlementRepository,
    private val entitlementEventRepository: EntitlementEventRepository,
    private val entitlementTxLedgerRepository: EntitlementTxLedgerRepository,
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
     * Entitlement 부여 — tx_ref 상태머신의 grant 전이 (클래스 doc 의 전이표 참조).
     *
     * 처리 순서 (V36 재설계 — 순서가 결함 방어의 핵심):
     *  1. (tx_ref, REVOKE) terminal 선확인 — REVOKE 가 먼저 도착한 토큰(순서 역전)의 grant 는
     *     권리를 생성하지 않고 [GrantResult.REVOKED] 멱등 반환.
     *  2. user_entitlement insertIfAbsent **먼저** — GRANT 원장 선점은 insert 성공 시에만.
     *     (구 구현의 "원장 선삽입 후 row insert" 는 row insert 가 no-op 인 경우에도 원장이
     *     커밋되어, 아무 권리도 만들지 않은 토큰의 정당한 재시도를 영구 차단했다.)
     *  3. 충돌(타 (user,key) 선점 토큰)은 본 메서드의 @Transactional **안에서** throw —
     *     entitlement/원장 기록이 함께 롤백된다.
     *
     * @param reason [EntitlementEvent.REASON_PURCHASE] / SYSTEM_GRANT / ADMIN_OVERRIDE
     * @param txRef Play purchaseToken or Apple transactionId (idempotency 키).
     *   null/blank 면 원장 미사용 (SYSTEM_GRANT / ADMIN_OVERRIDE — 기존 동작 유지).
     * @throws EntitlementTxRefConflictException tx_ref 를 타 (user,key) 가 선점 — tx 롤백
     */
    @Transactional
    fun grant(
        userId: String,
        entitlementKey: String,
        reason: String,
        txRef: String? = null,
    ): GrantResult {
        // ── [0] tx_ref 단위 직렬화 ──────────────────────────────────────────────
        // 같은 토큰의 grant/revoke 가 병렬 tx 로 겹치면 [1] 의 사전 SELECT 와 이후 원장
        // INSERT 사이에서 REVOKE 원장과 live entitlement 가 동시에 남을 수 있다 —
        // advisory lock 으로 토큰 단위 순차화 (tx 종료 시 자동 해제).
        if (!txRef.isNullOrBlank()) {
            entitlementTxLedgerRepository.acquireTxRefLock(txRef)
        }

        // ── [1] REVOKED terminal 선확인 ─────────────────────────────────────────
        // REVOKE 선도착(순서 역전) / 환불 후 grant 재전송 모두 여기서 차단 — 환불된 권리 미복원.
        if (!txRef.isNullOrBlank() &&
            entitlementTxLedgerRepository.findByTxRefAndAction(txRef, EntitlementTxLedger.ACTION_REVOKE) != null
        ) {
            log.info(
                "entitlement.grant.revoked-terminal user={} key={} — (tx_ref, REVOKE) 존재, grant 거부(멱등)",
                userId,
                entitlementKey,
            )
            return GrantResult.REVOKED
        }

        // ── [2] 현재 상태 insert 먼저 — 원장 선점은 insert 성공 시에만 ──────────────
        // native ON CONFLICT DO NOTHING (UserGrantRepository 패턴): PK(user_id,entitlement_key)
        // 또는 partial unique(uq_user_entitlement_tx_ref) 어느 충돌이든 예외 없이 0 rows —
        // check-then-save 의 tx rollback-only 오염(UnexpectedRollbackException) 없음.
        val inserted = userEntitlementRepository.insertIfAbsent(userId, entitlementKey, txRef)

        if (inserted == 1) {
            if (!txRef.isNullOrBlank()) {
                val claimed =
                    entitlementTxLedgerRepository.insertIfAbsent(userId, entitlementKey, txRef, EntitlementTxLedger.ACTION_GRANT)
                if (claimed == 0) {
                    val owner = entitlementTxLedgerRepository.findByTxRefAndAction(txRef, EntitlementTxLedger.ACTION_GRANT)
                    if (owner == null || owner.userId != userId || owner.entitlementKey != entitlementKey) {
                        // 타 (user,key) 가 GRANT 선점한 토큰의 replay. 같은 tx 안 throw 로 위의
                        // entitlement insert 까지 함께 롤백 — 잘못된 상태가 커밋되지 않는다.
                        // (owner null 은 이론상 도달 불가: insert 0 = committed row 존재 — 방어적 fail-loud.)
                        throwTxRefConflict(userId, entitlementKey, reason, txRef)
                    }
                    // 같은 (user,key) 의 기존 GRANT 기록 위에 row 재부여 — 원장 REVOKE 없이 row 만
                    // 사라진 레거시/수동 삭제 후 재전송 케이스. 원장은 이미 정확(GRANT 1회)하므로
                    // 재부여를 그대로 진행한다.
                }
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

        // ── [3] inserted == 0 — PK(이미 보유) vs tx_ref 선점(실제 충돌) 판정 ─────────
        val existing = userEntitlementRepository.findById(UserEntitlementId(userId, entitlementKey)).orElse(null)
        if (existing == null) {
            // 이 (user,key) 는 미보유인데 insert 가 막힘 = uq_user_entitlement_tx_ref 충돌 —
            // 같은 토큰을 타 (user,key) 의 live row 가 보유 중. 실제 실패로 escalate.
            // (구 Boolean false 뭉갬은 webhook 200 → 재전송 duplicate 차단 → 결제 영구 유실이었음.)
            throwTxRefConflict(userId, entitlementKey, reason, txRef)
        }
        if (txRef.isNullOrBlank() || existing.txRef == txRef) {
            // 동일 토큰(또는 무토큰) 재전송 — 정상 멱등. V36 이전 부여분의 원장 누락(backfill 사각)
            // 대비 insert-if-absent 보강 (이미 있으면 no-op — 무해).
            if (!txRef.isNullOrBlank()) {
                entitlementTxLedgerRepository.insertIfAbsent(userId, entitlementKey, txRef, EntitlementTxLedger.ACTION_GRANT)
            }
            log.info("entitlement.grant.skip user={} key={} (already granted)", userId, entitlementKey)
            return GrantResult.ALREADY_PRESENT
        }

        // 다른(또는 null) tx_ref 로 이미 보유 중 — 이 토큰의 처리 이력은 원장 소유자로 판정.
        val owner = entitlementTxLedgerRepository.findByTxRefAndAction(txRef, EntitlementTxLedger.ACTION_GRANT)
        return when {
            owner == null -> {
                // 이 토큰은 아직 아무 권리도 생성하지 않았다 — 여기서 GRANT 원장을 쓰면(구 구현)
                // 기존 권리가 회수된 뒤 이 토큰의 정당한 재시도가 "이미 처리됨"으로 영구 차단된다.
                // **원장 기록 없이** 멱등 반환해 토큰 미선점(UNSEEN) 상태를 유지한다.
                log.info(
                    "entitlement.grant.skip user={} key={} (already granted — 미처리 tx_ref 는 미선점 유지)",
                    userId,
                    entitlementKey,
                )
                GrantResult.ALREADY_PRESENT
            }
            owner.userId == userId && owner.entitlementKey == entitlementKey -> {
                // 같은 (user,key) 가 과거 처리한 토큰의 재전송 — 무해 멱등.
                log.info("entitlement.grant.skip user={} key={} (tx_ref GRANT 이미 처리됨)", userId, entitlementKey)
                GrantResult.ALREADY_PRESENT
            }
            // 타 (user,key) 소유 토큰의 replay — 실제 충돌.
            else -> throwTxRefConflict(userId, entitlementKey, reason, txRef)
        }
    }

    /**
     * tx_ref 충돌 escalate — 서비스 tx 안 throw (entitlement/원장 롤백 동반).
     * audit publish 는 호출부(컨트롤러, tx 밖) 책임 — tx 안 publish 는 롤백과 함께 유실된다
     * (AuditEventListener 는 AFTER_COMMIT + fallbackExecution=true — tx 부재 시에만 fallback 동작).
     * log.error 가 tx 무관 최소 보장.
     */
    private fun throwTxRefConflict(
        userId: String,
        entitlementKey: String,
        reason: String,
        txRef: String?,
    ): Nothing {
        log.error(
            "entitlement.grant.txref-conflict user={} key={} txRef={} — tx_ref 가 다른 entitlement 에 이미 사용됨",
            userId,
            entitlementKey,
            txRef,
        )
        throw EntitlementTxRefConflictException(userId, entitlementKey, txRef, reason)
    }

    /**
     * Entitlement 회수 — tx_ref 상태머신의 revoke 전이 (클래스 doc 의 전이표 참조).
     *
     * V36 재설계 (순서가 결함 방어의 핵심):
     *  - txRef 있는 경로: (tx_ref, REVOKE) 원장 **선점을 삭제보다 먼저** 수행.
     *    - 선점 실패(0 rows) = 이미 REVOKE 처리된 토큰의 중복 재전송 — 현재 row 는 (있다면)
     *      다른 토큰의 재구매분일 수 있으므로 **어떤 row 도 삭제하지 않고** 반환.
     *    - 선점 성공 시 삭제는 현재 row 의 tx_ref 가 요청 토큰과 일치할 때만 (조건부 원자 DELETE —
     *      이 토큰이 만들지 않은 권리는 지우지 않는다).
     *  - txRef 없는 fallback(관리자 수동 회수 등): 단일 원자 DELETE ... RETURNING tx_ref 로
     *    "읽은 row = 삭제한 row" 를 보장 — 잠금 없는 SELECT 후 DELETE 는 사이에 row 가
     *    교체되면 다른 토큰의 row 를 지우고 원장에는 옛 토큰을 기록하는 race 였다.
     *
     * PAY-005 (유지): 삭제 0건이어도 REVOKE 이력(entitlement_event) + audit 기록 —
     * reason suffix 로 구분 (`_NO_PRIOR_GRANT` = 삭제 대상 없음 / `_ALREADY_REVOKED` = 중복 재전송).
     *
     * Soft-effect (자유배치 → 슬롯 fallback) 는 호출자가 PlacementController 통해 처리.
     *
     * @return true = row 삭제됨 / false = 삭제 없음 (중복 revoke / 대상 부재 — 멱등)
     */
    @Transactional
    fun revoke(
        userId: String,
        entitlementKey: String,
        reason: String,
        txRef: String? = null,
    ): Boolean {
        if (!txRef.isNullOrBlank()) {
            // tx_ref 단위 직렬화 — grant() 의 [0] 과 동일한 lock (동시 grant/revoke 순차화).
            entitlementTxLedgerRepository.acquireTxRefLock(txRef)
            // ── (tx_ref, REVOKE) 원장 선점 먼저 ──────────────────────────────────
            val claimed =
                entitlementTxLedgerRepository.insertIfAbsent(userId, entitlementKey, txRef, EntitlementTxLedger.ACTION_REVOKE)
            if (claimed == 0) {
                // 중복 REVOKE 재전송 — terminal 상태 불변. 현재 row 를 삭제하면 같은 (user,key)
                // 의 재구매(다른 토큰) 권리를 지우게 된다 — 삭제 금지.
                log.info(
                    "entitlement.revoke.duplicate user={} key={} (tx_ref REVOKE 이미 처리 — 삭제 없이 멱등 반환)",
                    userId,
                    entitlementKey,
                )
                recordRevoke(userId, entitlementKey, "${reason}_ALREADY_REVOKED", txRef, rowDeleted = false)
                return false
            }
            // 신규 REVOKE — 이 토큰이 만든 권리(tx_ref 일치 row)만 조건부 원자 삭제.
            val deleted = userEntitlementRepository.deleteIfTxRefMatches(userId, entitlementKey, txRef)
            val effectiveReason = if (deleted == 0) "${reason}_NO_PRIOR_GRANT" else reason
            recordRevoke(userId, entitlementKey, effectiveReason, txRef, rowDeleted = deleted > 0)
            return deleted > 0
        }

        // ── txRef 없는 fallback — 단일 원자 DELETE ... RETURNING tx_ref ─────────────
        val returnedTxRefs = userEntitlementRepository.deleteReturningTxRef(userId, entitlementKey)
        val deleted = returnedTxRefs.isNotEmpty() // PK 단건 — 0 또는 1 row
        val rowTxRef = returnedTxRefs.firstOrNull()
        if (!rowTxRef.isNullOrBlank()) {
            // 삭제한 바로 그 row 의 tx_ref 로 REVOKE 원장 기록 — 그 토큰의 grant 재전송을 terminal 차단.
            entitlementTxLedgerRepository.insertIfAbsent(userId, entitlementKey, rowTxRef, EntitlementTxLedger.ACTION_REVOKE)
        }
        val effectiveReason = if (!deleted) "${reason}_NO_PRIOR_GRANT" else reason
        recordRevoke(userId, entitlementKey, effectiveReason, rowTxRef, rowDeleted = deleted)
        return deleted
    }

    /** revoke 공통 기록 — entitlement_event 이력 + audit + 로그 (PAY-005: 삭제 0건도 흔적 보존). */
    private fun recordRevoke(
        userId: String,
        entitlementKey: String,
        effectiveReason: String,
        txRef: String?,
        rowDeleted: Boolean,
    ) {
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
                    "rowDeleted" to rowDeleted,
                ),
        )
        if (rowDeleted) {
            log.warn("entitlement.revoke user={} key={} reason={}", userId, entitlementKey, effectiveReason)
        } else {
            log.info("entitlement.revoke.no-delete user={} key={} reason={}", userId, entitlementKey, effectiveReason)
        }
    }
}
