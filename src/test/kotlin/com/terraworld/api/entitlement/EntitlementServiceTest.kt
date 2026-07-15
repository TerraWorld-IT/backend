package com.terraworld.api.entitlement

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.entitlement.EntitlementEvent
import com.terraworld.domain.entitlement.EntitlementEventRepository
import com.terraworld.domain.entitlement.UserEntitlement
import com.terraworld.domain.entitlement.UserEntitlementId
import com.terraworld.domain.entitlement.UserEntitlementRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * EntitlementService 의 분기 커버.
 * - grant: 해피 패스 (row + GRANT ledger) / idempotency (중복 grant 시 ledger 1회만) /
 *          R4-ENT-01 — tx_ref 가 다른 row 에 선점된 경우 TX_REF_CONFLICT (멱등 아님)
 * - revoke: 해피 패스 (row 삭제 + REVOKE ledger) /
 *           PAY-005 — 존재하지 않는 entitlement revoke 시에도 REVOKE ledger 보강 (reason suffix)
 */
class EntitlementServiceTest {
    private lateinit var entitlementRepo: FakeUserEntitlementRepository
    private lateinit var eventRepo: FakeEntitlementEventRepository
    private lateinit var service: EntitlementService

    @BeforeEach
    fun setup() {
        entitlementRepo = FakeUserEntitlementRepository()
        eventRepo = FakeEntitlementEventRepository()
        // AuditService 는 side-effect publisher — 검증 대상 아님, mock.
        val auditService = org.mockito.Mockito.mock(AuditService::class.java)
        service =
            EntitlementService(
                entitlementRepo,
                eventRepo,
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
    fun `grant R4-ENT-01 — 같은 txRef 를 다른 (user,key) 가 선점하면 TX_REF_CONFLICT + row·ledger 미생성`() {
        // uq_user_entitlement_tx_ref (partial unique) 충돌: user-1 이 tok-1 로 이미 grant 된 상태에서
        // user-2 가 같은 tok-1 로 grant 시도 — "이미 보유" 멱등이 아니라 실제 실패로 분류돼야 한다.
        // (구 Boolean false 뭉갬은 webhook 200 → 재전송 duplicate 차단 → 결제 영구 유실이었음.)
        service.grant("user-1", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        val result =
            service.grant("user-2", UserEntitlementId.FREE_PLACEMENT, EntitlementEvent.REASON_PURCHASE, txRef = "tok-1")

        assertEquals(GrantResult.TX_REF_CONFLICT, result)
        // user-2 에게는 row 도 GRANT ledger 도 생기면 안 됨
        assertFalse(entitlementRepo.existsByIdUserIdAndIdEntitlementKey("user-2", UserEntitlementId.FREE_PLACEMENT))
        assertEquals(0, eventRepo.findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc("user-2", UserEntitlementId.FREE_PLACEMENT).size)
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

        // row 가 없어 deleted == 0 → false 반환, 단 ledger 는 보강돼야 함 (missed audit 회피).
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

        override fun deleteByIdUserIdAndIdEntitlementKey(
            userId: String,
            entitlementKey: String,
        ): Long {
            val match = store.values.filter { it.id.userId == userId && it.id.entitlementKey == entitlementKey }
            match.forEach { store.remove(it.id) }
            return match.size.toLong()
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
