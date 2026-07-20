package com.terraworld.api.entitlement

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.domain.entitlement.EntitlementEventRepository
import com.terraworld.domain.entitlement.EntitlementTxLedger
import com.terraworld.domain.entitlement.EntitlementTxLedgerRepository
import com.terraworld.domain.entitlement.UserEntitlement
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.entitlement.UserEntitlementRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * EntitlementService — tx_ref terminal-state 상태머신 (V36 재설계, 2026-07-15 적대적 리뷰 2라운드) 분기 커버.
 *
 * grant:
 *  - 해피 패스 / 중복 grant 멱등(ALREADY_PRESENT)
 *  - 결함 1: REVOKE 선도착(순서 역전) 후 grant = REVOKED + 권리 미생성
 *  - 결함 3: 다른 토큰으로 보유 중 새 토큰 grant = 원장 미선점 → 회수 후 정당한 재시도 가능
 *  - 충돌: live 토큰 replay / 원장 선점 토큰 replay = EntitlementTxRefConflictException (서비스 tx 안 throw)
 * revoke:
 *  - 결함 2: (tx_ref, REVOKE) 선점 먼저 — 중복 REVOKE 는 어떤 row 도 삭제하지 않음 (재구매 보호)
 *  - 결함 2: 신규 REVOKE 라도 삭제는 tx_ref 일치 row 만 (조건부 DELETE)
 *  - 결함 4: txRef 없는 fallback = 단일 원자 DELETE ... RETURNING tx_ref
 *  - PAY-005: 삭제 0건도 REVOKE 이력 보존 (reason suffix 로 구분)
 */
class EntitlementServiceTest {
    private lateinit var entitlementRepo: FakeUserEntitlementRepository
    private lateinit var eventRepo: FakeEntitlementEventRepository
    private lateinit var txLedgerRepo: FakeEntitlementTxLedgerRepository
    private lateinit var service: EntitlementService

    @BeforeEach
    fun setup() {
        entitlementRepo = FakeUserEntitlementRepository()
        eventRepo = FakeEntitlementEventRepository()
        txLedgerRepo = FakeEntitlementTxLedgerRepository()
        // AuditService 는 side-effect publisher — 검증 대상 아님, mock.
        val auditService = org.mockito.Mockito.mock(AuditService::class.java)
        service =
            EntitlementService(
                entitlementRepo,
                eventRepo,
                txLedgerRepo,
                auditService,
            )
    }

    // ─── grant ─────────────────────────────────────────────────

    @Test
    fun `grant 해피 패스 — row 생성 + GRANT ledger 1건`() {
        val granted =
            service.grant(
                userId = "user-1",
                entitlementKey = UserEntitlementId.FREE_PLACEMENT,
                reason = EntitlementEvent.REASON_PURCHASE,
                txRef = "tok-1",
            )

        assertEquals(GrantResult.GRANTED, granted)
        assertTrue(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-1", UserEntitlementId.FREE_PLACEMENT))

        val ledger = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(1, ledger.size)
        assertEquals(EntitlementEvent.ACTION_GRANT, ledger[0].action)
        assertEquals(EntitlementEvent.REASON_PURCHASE, ledger[0].reason)
        assertEquals("tok-1", ledger[0].txRef)
        // 처리 원장에도 (tok-1, GRANT) 선점
        assertEquals("user-1", txLedgerRepo.findByTxRefAndAction("tok-1", EntitlementTxLedger.ACTION_GRANT)?.userId)
    }

    @Test
    fun `grant idempotency — 중복 grant 시 ALREADY_PRESENT + 원장 1건만 유지`() {
        val first =
            service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        val second =
            service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.GRANTED, first)
        assertEquals(GrantResult.ALREADY_PRESENT, second)

        // 중복 grant 는 row 도 ledger 도 추가되면 안 됨 (Play purchaseToken 중복 webhook 대응).
        assertEquals(1, entitlementRepo.count())
        val ledger = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(1, ledger.size)
    }

    @Test
    fun `충돌 — live 토큰을 타 (user,key) 가 replay 하면 throw + 아무것도 기록되지 않음`() {
        // V36 재설계 (구 R4-ENT-01 pin 갱신): TX_REF_CONFLICT 값 반환 대신 서비스 @Transactional
        // 안에서 EntitlementTxRefConflictException throw — 잘못 선점된 원장/row 가 커밋되지 않는다.
        // (구 Boolean false 뭉갬은 webhook 200 → 재전송 duplicate 차단 → 결제 영구 유실이었음.)
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertThrows<EntitlementTxRefConflictException> {
            service.grant("user-2", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        }

        // 이 경로는 throw 이전에 어떤 쓰기도 없다 — user-2 의 row/이력/원장 모두 부재.
        assertFalse(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-2", UserEntitlementId.FREE_PLACEMENT))
        assertEquals(0, eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-2", UserEntitlementId.FREE_PLACEMENT).size)
        assertEquals("user-1", txLedgerRepo.findByTxRefAndAction("tok-1", EntitlementTxLedger.ACTION_GRANT)?.userId)
    }

    @Test
    fun `결함 1 — REVOKE 선도착(순서 역전) 후 같은 tx_ref grant = REVOKED + 권리 미생성`() {
        // refund webhook 이 grant webhook 보다 먼저 도착한 순서 역전. 구 구현은 (tx_ref, GRANT)
        // row 만 검사해 (tx_ref, REVOKE) 가 있어도 grant 를 진행 — 환불된 권리를 생성했다.
        // REVOKED 는 terminal: grant 는 권리를 만들지 않고 멱등 반환해야 한다.
        val revoked = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-1")
        assertFalse(revoked) // 삭제할 row 없음 — 단 (tok-1, REVOKE) terminal 은 확정됨

        val granted = service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.REVOKED, granted)
        assertFalse(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        // GRANT 원장/이력도 미기록 — terminal 선확인이 insert 이전이다.
        assertNull(txLedgerRepo.findByTxRefAndAction("tok-1", EntitlementTxLedger.ACTION_GRANT))
    }

    @Test
    fun `결함 1 — revoke 후 같은 tx_ref grant 재전송 = REVOKED + 권리 미복원`() {
        // 핵심 시나리오 (V36 원안 pin 갱신 — ALREADY_PRESENT → REVOKED): tx_ref 유일성이 현재 상태
        // 테이블 row 에만 있으면 revoke 후 grant 재전송이 취소·환불된 권리를 복원한다. 처리 원장의
        // (tx_ref, REVOKE) terminal 이 row 존재 여부와 무관하게 차단. 호출부 계약은 동일하게 멱등
        // (webhook 200 ACK / IAP granted=false) — REVOKED 라벨이 상태를 더 정확히 표현할 뿐이다.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-1")

        val redelivered =
            service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.REVOKED, redelivered)
        assertFalse(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-1", UserEntitlementId.FREE_PLACEMENT))
        assertFalse(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        // 감사 이력(entitlement_event)도 GRANT 1 + REVOKE 1 그대로 (재전송이 GRANT 추가 금지).
        val events = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(2, events.size)
    }

    @Test
    fun `결함 1 — revoke 된 tx_ref 는 타 (user,key) 의 replay 도 REVOKED (권리 미생성)`() {
        // pin 시맨틱 갱신 (구: TX_REF_CONFLICT): REVOKED terminal 선확인이 소유 판정보다 앞선다 —
        // 환불된 토큰은 누가 replay 해도 권리를 만들지 못하며(동일 보안 결과), 결과가 불변인
        // 재전송에 5xx 재시도를 유도할 이유가 없어 멱등(REVOKED) 반환이 옳다.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-1")

        val replay =
            service.grant("user-2", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.REVOKED, replay)
        assertFalse(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-2", UserEntitlementId.FREE_PLACEMENT))
    }

    @Test
    fun `결함 3 — 다른 토큰으로 보유 중 새 토큰 grant 는 원장 미선점 → 회수 후 재시도 grant 가능`() {
        // 구 구현: 원장 선삽입 후 entitlement insert 가 no-op(이미 보유)여도 (tok-B, GRANT) 가
        // 커밋 → tok-B 는 아무 권리도 만들지 않았는데 "이미 처리됨"으로 영구 차단됐다.
        // 재설계: 원장 선점은 entitlement insert 성공 시에만 — tok-B 는 미선점(UNSEEN) 유지.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-A")

        val second = service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-B")
        // 2026-07-20 Codex R1 HIGH: 미선점 다른 토큰은 일반 ALREADY_PRESENT 와 구분 —
        // IAP 클라가 이 토큰을 finish(alreadyOwned) 하면 회수 후 재시도 경로가 사라진다.
        assertEquals(GrantResult.ALREADY_PRESENT_UNSEEN_TOKEN, second)
        // 핵심: tok-B 의 GRANT 원장이 선점되지 않았다.
        assertNull(txLedgerRepo.findByTxRefAndAction("tok-B", EntitlementTxLedger.ACTION_GRANT))

        // 기존 권리(tok-A) 회수 후 tok-B 의 정당한 재시도는 성공해야 한다.
        service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-A")
        val retry = service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-B")

        assertEquals(GrantResult.GRANTED, retry)
        assertTrue(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        assertEquals("user-1", txLedgerRepo.findByTxRefAndAction("tok-B", EntitlementTxLedger.ACTION_GRANT)?.userId)
    }

    @Test
    fun `레거시 edge — 원장 GRANT 만 있고 row 없는 상태의 같은 (user,key) 재전송은 GRANTED 재부여`() {
        // 원장 REVOKE 없이 row 만 사라진 케이스 (V36 이전 수동 삭제 등). 원장은 이미
        // "이 토큰은 이 (user,key) 가 GRANT 처리" 를 정확히 기록 중 — 재전송 재부여 허용.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        entitlementRepo.deleteById(UserEntitlementId("user-1", UserEntitlementId.FREE_PLACEMENT)) // 원장 미기록 삭제 모사

        val regrant = service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.GRANTED, regrant)
        assertTrue(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
    }

    @Test
    fun `레거시 edge — 원장 GRANT 만 있고 row 없는 토큰을 타 (user,key) 가 시도하면 throw`() {
        // 타 (user,key) 소유 원장 기록이 있는 토큰의 replay — 실제 충돌 (서비스 tx 안 throw 로
        // entitlement insert 가 함께 롤백된다. Fake 는 tx 미모사 — throw 전파만 pin 하고,
        // 롤백 자체는 Spring @Transactional 의 rollback-on-RuntimeException 동작에 위임).
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        entitlementRepo.deleteById(UserEntitlementId("user-1", UserEntitlementId.FREE_PLACEMENT)) // 원장 미기록 삭제 모사

        assertThrows<EntitlementTxRefConflictException> {
            service.grant("user-2", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        }
    }

    @Test
    fun `grant V36 — tx_ref 없는 grant(SYSTEM_GRANT 등)는 처리 원장 미기록 (기존 동작 유지)`() {
        val granted = service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_SYSTEM_GRANT)

        assertEquals(GrantResult.GRANTED, granted)
        assertEquals(0, txLedgerRepo.count())
    }

    // ─── revoke ────────────────────────────────────────────────

    @Test
    fun `revoke 해피 패스 — row 삭제 + REVOKE ledger 추가`() {
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        val revoked =
            service.revoke(
                userId = "user-1",
                entitlementKey = UserEntitlementId.FREE_PLACEMENT,
                reason = EntitlementEvent.REASON_REFUND,
                txRef = "tok-1",
            )

        assertTrue(revoked)
        assertFalse(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-1", UserEntitlementId.FREE_PLACEMENT))

        // GRANT + REVOKE = 2 ledger. 최신(REVOKE) 이 reason suffix 없이 그대로.
        val ledger = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(2, ledger.size)
        val revokeEvent = ledger.first { it.action == EntitlementEvent.ACTION_REVOKE }
        assertEquals(EntitlementEvent.REASON_REFUND, revokeEvent.reason)

        // V36: 처리 원장에도 (tok-1, REVOKE) 기록 — GRANT 1 + REVOKE 1.
        val revokeLedger = txLedgerRepo.findByTxRefAndAction("tok-1", EntitlementTxLedger.ACTION_REVOKE)
        assertEquals("user-1", revokeLedger?.userId)
    }

    @Test
    fun `결함 2 — 중복 REVOKE 재전송은 재구매(다른 토큰) row 를 삭제하지 않는다`() {
        // 토큰 A grant→revoke 후 토큰 B 로 재구매한 상태에서 A 의 revoke 가 재전송되는 시나리오.
        // 구 구현: row 삭제 먼저 + REVOKE 원장 insert 결과 무시 → A 재전송이 B(재구매) row 를
        // 삭제했다. 재설계: (tx_ref, REVOKE) 선점이 먼저 — 0 rows(중복)면 삭제 없이 반환.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-A")
        service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-A")
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-B")

        val redelivered = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-A")

        assertFalse(redelivered)
        // 핵심: 재구매(tok-B) row 생존.
        assertTrue(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        // PAY-005 정신: 중복 재전송도 이력에 흔적 (`_ALREADY_REVOKED` suffix).
        val events = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertTrue(events.any { it.reason == "${EntitlementEvent.REASON_REFUND}_ALREADY_REVOKED" })
    }

    @Test
    fun `결함 2 — 신규 REVOKE 라도 현재 row 의 tx_ref 가 다르면 삭제하지 않는다 (조건부 DELETE)`() {
        // (tok-X, REVOKE) 선점에 성공한 최초 revoke 라도, 현재 row 가 다른 토큰(tok-A)의
        // 권리라면 이 토큰이 만든 권리가 아니다 — 삭제 금지 + `_NO_PRIOR_GRANT` 이력.
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-A")

        val revoked = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_REFUND, txRef = "tok-X")

        assertFalse(revoked)
        assertTrue(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        val events = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertTrue(events.any { it.reason == "${EntitlementEvent.REASON_REFUND}_NO_PRIOR_GRANT" })
        // tok-X 는 REVOKE terminal 로 확정 — 이후 tok-X grant 는 REVOKED.
        assertEquals(GrantResult.REVOKED, service.grant("user-2", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-X"))
    }

    @Test
    fun `결함 4 — txRef 미전달 fallback 은 원자 DELETE RETURNING 으로 삭제한 row 의 tx_ref 를 원장 기록`() {
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        // 호출부가 tx_ref 를 모르는 revoke 경로 (예: 관리자 수동 회수) — 삭제와 tx_ref 읽기가
        // 단일 원자 연산 (구 구현의 잠금 없는 findById 후 DELETE 는 사이에 row 교체 시
        // 다른 토큰 row 를 지우고 원장을 오염시키는 race).
        val revoked = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_ADMIN_OVERRIDE)

        assertTrue(revoked)
        val revokeLedger = txLedgerRepo.findByTxRefAndAction("tok-1", EntitlementTxLedger.ACTION_REVOKE)
        assertEquals("user-1", revokeLedger?.userId)

        // fallback 기록 이후에도 같은 tok-1 grant 재전송은 REVOKED terminal — 권리 미복원.
        val redelivered =
            service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")
        assertEquals(GrantResult.REVOKED, redelivered)
        assertFalse(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
    }

    @Test
    fun `결함 4 — fallback 대상 row 부재면 false + 원장 무기록`() {
        val revoked = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_ADMIN_OVERRIDE)

        assertFalse(revoked)
        assertEquals(0, txLedgerRepo.count())
        // PAY-005: 삭제 0건도 이력은 남는다.
        val events = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-1", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(1, events.size)
        assertEquals("${EntitlementEvent.REASON_ADMIN_OVERRIDE}_NO_PRIOR_GRANT", events[0].reason)
    }

    @Test
    fun `결함 4 — fallback 이 무토큰(tx_ref null) row 를 삭제하면 원장은 기록하지 않는다`() {
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_SYSTEM_GRANT) // tx_ref 없음

        val revoked = service.revoke("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_ADMIN_OVERRIDE)

        assertTrue(revoked)
        assertFalse(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        assertEquals(0, txLedgerRepo.count())
    }

    @Test
    fun `revoke PAY-005 — 존재하지 않는 entitlement 도 REVOKE ledger 보강 (suffix)`() {
        val revoked =
            service.revoke(
                userId = "user-ghost",
                entitlementKey = UserEntitlementId.FREE_PLACEMENT,
                reason = EntitlementEvent.REASON_REFUND,
                txRef = "tok-ghost",
            )

        // row 가 없어 삭제 0건 → false 반환, 단 ledger 는 보강돼야 함 (missed audit 회피).
        assertFalse(revoked)

        val ledger = eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-ghost", UserEntitlementId.FREE_PLACEMENT)
        assertEquals(1, ledger.size)
        assertEquals(EntitlementEvent.ACTION_REVOKE, ledger[0].action)
        // reason 에 `_NO_PRIOR_GRANT` suffix 가 붙어 idempotent revoke 임을 구분.
        assertEquals("${EntitlementEvent.REASON_REFUND}_NO_PRIOR_GRANT", ledger[0].reason)
    }

    // ─── hasEntitlement / listEntitlements ─────────────────────

    @Test
    fun `hasEntitlement 과 listEntitlements — grant 후 반영`() {
        assertFalse(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        assertTrue(service.listEntitlements("user-1").isEmpty())

        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_SYSTEM_GRANT)

        assertTrue(service.hasEntitlement("user-1", UserEntitlementId.FREE_PLACEMENT))
        assertEquals(listOf(UserEntitlementId.FREE_PLACEMENT), service.listEntitlements("user-1"))
    }

    // ─── Fakes ─────────────────────────────────────────────────

    private class FakeUserEntitlementRepository :
        FakeJpaRepository<UserEntitlement, UserEntitlementId>(),
        UserEntitlementRepository {
        override fun extractId(entity: UserEntitlement): UserEntitlementId = entity.id

        override fun findAllByIdUserId(userId: String): List<UserEntitlement> = store.values.filter { it.id.userId == userId }

        override fun existsByIdUserIdAndIdEntitlementKey(
            userId: String,
            entitlementKey: String,
        ): Boolean = store.values.any { it.id.userId == userId && it.id.entitlementKey == entitlementKey }

        override fun deleteIfTxRefMatches(
            userId: String,
            entitlementKey: String,
            txRef: String,
        ): Int {
            // 조건부 원자 DELETE 모사 — tx_ref 일치 row 만 삭제.
            val id = UserEntitlementId(userId, entitlementKey)
            val row = store[id] ?: return 0
            if (row.txRef != txRef) return 0
            store.remove(id)
            return 1
        }

        override fun deleteReturningTxRef(
            userId: String,
            entitlementKey: String,
        ): List<String?> {
            // DELETE ... RETURNING tx_ref 모사 — 삭제한 바로 그 row 의 tx_ref 반환.
            val id = UserEntitlementId(userId, entitlementKey)
            val row = store.remove(id) ?: return emptyList()
            return listOf(row.txRef)
        }

        override fun insertIfAbsent(
            userId: String,
            entitlementKey: String,
            txRef: String?,
        ): Int {
            val id = UserEntitlementId(userId, entitlementKey)
            // PK (user_id, entitlement_key) 충돌 모사
            if (store.containsKey(id)) return 0
            // uq_user_entitlement_tx_ref 모사 — partial unique (tx_ref NOT NULL, 전역)
            if (txRef != null && store.values.any { it.txRef == txRef }) return 0
            store[id] = UserEntitlement(id = id, txRef = txRef)
            return 1
        }
    }

    private class FakeEntitlementTxLedgerRepository :
        FakeJpaRepository<EntitlementTxLedger, Long>(),
        EntitlementTxLedgerRepository {
        private val seq = AtomicLong(0)

        override fun extractId(entity: EntitlementTxLedger): Long = entity.id

        override fun assignId(entity: EntitlementTxLedger): EntitlementTxLedger {
            if (entity.id == 0L) {
                val field = EntitlementTxLedger::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.setLong(entity, seq.incrementAndGet())
            }
            return entity
        }

        // advisory lock 은 단일 스레드 단위 테스트에서 no-op (실 직렬화는 PG 가 담당).
        override fun acquireTxRefLock(txRef: String): Int = 1

        override fun findByTxRefAndAction(
            txRef: String,
            action: String,
        ): EntitlementTxLedger? = store.values.firstOrNull { it.txRef == txRef && it.action == action }

        override fun insertIfAbsent(
            userId: String,
            entitlementKey: String,
            txRef: String,
            action: String,
        ): Int {
            // uq_entitlement_tx_ledger_ref_action 모사 — UNIQUE (tx_ref, action)
            if (store.values.any { it.txRef == txRef && it.action == action }) return 0
            save(EntitlementTxLedger(userId = userId, entitlementKey = entitlementKey, txRef = txRef, action = action))
            return 1
        }
    }

    private class FakeEntitlementEventRepository :
        FakeJpaRepository<EntitlementEvent, Long>(),
        EntitlementEventRepository {
        private val seq = AtomicLong(0)

        override fun extractId(entity: EntitlementEvent): Long = entity.id

        // id 는 IDENTITY auto-generated — 0 이면 reflection 으로 새 값 할당.
        override fun assignId(entity: EntitlementEvent): EntitlementEvent {
            if (entity.id == 0L) {
                val field = EntitlementEvent::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.setLong(entity, seq.incrementAndGet())
            }
            return entity
        }

        override fun findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc(
            userId: String,
            entitlementKey: String,
        ): List<EntitlementEvent> =
            store.values
                .filter { it.userId == userId && it.entitlementKey == entitlementKey }
                .sortedByDescending { it.occurredAt }

        override fun findAllByUserIdOrderByOccurredAtDesc(userId: String): List<EntitlementEvent> =
            store.values
                .filter { it.userId == userId }
                .sortedByDescending { it.occurredAt }
    }
}
