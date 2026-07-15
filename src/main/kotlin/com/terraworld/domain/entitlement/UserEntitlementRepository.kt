package com.terraworld.domain.entitlement

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository

@Repository
interface UserEntitlementRepository : JpaRepository<UserEntitlement, UserEntitlementId> {
    fun findAllByIdUserId(userId: String): List<UserEntitlement>

    fun existsByIdUserIdAndIdEntitlementKey(
        userId: String,
        entitlementKey: String,
    ): Boolean

    /**
     * V36 재설계 (결함 2): tx_ref 조건부 원자 DELETE — 현재 row 의 tx_ref 가 요청 토큰과
     * 일치할 때만 삭제한다. (tx_ref, REVOKE) 원장 선점에 성공한 신규 revoke 라도, 그 사이
     * 같은 (user,key) 가 다른 토큰으로 재구매한 row 는 이 토큰이 만든 권리가 아니므로
     * 지우면 안 된다 — 단일 statement 로 판정+삭제를 원자 수행 (SELECT 후 DELETE race 제거).
     *
     * @return 1 = 삭제됨 / 0 = 대상 없음 (row 부재 또는 tx_ref 불일치)
     */
    @Modifying
    @Query(
        value =
            """
            DELETE FROM user_entitlement
            WHERE user_id = :userId AND entitlement_key = :entitlementKey AND tx_ref = :txRef
            """,
        nativeQuery = true,
    )
    fun deleteIfTxRefMatches(
        @Param("userId") userId: String,
        @Param("entitlementKey") entitlementKey: String,
        @Param("txRef") txRef: String,
    ): Int

    /**
     * V36 재설계 (결함 4): txRef 없는 revoke fallback 용 단일 원자 DELETE ... RETURNING.
     *
     * 기존의 잠금 없는 findById(SELECT) 후 (user,key) DELETE 는 사이에 row 가 교체되면
     * "읽은 tx_ref ≠ 삭제한 row" 가 되어 다른 토큰의 row 를 지우고 원장을 오염시켰다.
     * RETURNING 이 삭제된 바로 그 row 의 tx_ref 를 같은 statement 로 반환 — 원자성 보장.
     *
     * RETURNING 이 결과 집합을 내므로 @Modifying 없이 result 쿼리로 실행한다
     * (UserCurrencyBalanceRepository.creditAtomic BE-09 와 동일 패턴 — Hibernate 는
     * query-space 미지정 native 쿼리 실행 전 영속성 컨텍스트를 flush).
     *
     * @return 빈 리스트 = 삭제된 row 없음 / [tx_ref] = 삭제됨 (원소가 null 이면 무토큰 row —
     *   PK (user_id, entitlement_key) 이므로 최대 1건)
     */
    @Query(
        value =
            """
            DELETE FROM user_entitlement
            WHERE user_id = :userId AND entitlement_key = :entitlementKey
            RETURNING tx_ref
            """,
        nativeQuery = true,
    )
    fun deleteReturningTxRef(
        @Param("userId") userId: String,
        @Param("entitlementKey") entitlementKey: String,
    ): List<String?>

    /**
     * 원자적 멱등 insert (UserGrantRepository.insertIfAbsent 패턴 승계). 동일 grant 가 동시 도착 시
     * 한 쪽만 1(inserted), 다른 쪽 0(conflict) 반환.
     *
     * check-then-save(existsBy... → JPA save) 대신 native ON CONFLICT DO NOTHING 을 쓰는 이유:
     * JPA save 방식은 UNIQUE/PK 위반 시 Spring 이 트랜잭션을 rollback-only 로 마킹 →
     * catch 후 정상 반환해도 커밋 시 `UnexpectedRollbackException` 발생(실버그) + 500 응답.
     * native ON CONFLICT 는 예외를 발생시키지 않아 tx 오염이 없다.
     *
     * conflict target 을 명시하지 않은 bare `ON CONFLICT DO NOTHING` — PK (user_id, entitlement_key)
     * 와 partial unique index (uq_user_entitlement_tx_ref, tx_ref WHERE NOT NULL) 둘 중 어느 제약이
     * 위반돼도 no-op 처리(단일 statement 로는 conflict target 한 개만 명시 가능하므로 bare 사용).
     *
     * @return 1 = 신규 부여 / 0 = 이미 부여됨(PK 또는 tx_ref 충돌 — 멱등 no-op)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO user_entitlement (user_id, entitlement_key, granted_at, tx_ref)
            VALUES (:userId, :entitlementKey, NOW(), :txRef)
            ON CONFLICT DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("entitlementKey") entitlementKey: String,
        @Param("txRef") txRef: String?,
    ): Int
}

@Repository
interface EntitlementEventRepository : JpaRepository<EntitlementEvent, Long> {
    fun findAllByUserIdAndEntitlementKeyOrderByOccurredAtDesc(
        userId: String,
        entitlementKey: String,
    ): List<EntitlementEvent>

    fun findAllByUserIdOrderByOccurredAtDesc(userId: String): List<EntitlementEvent>
}

@Repository
interface EntitlementTxLedgerRepository : JpaRepository<EntitlementTxLedger, Long> {
    /**
     * V36 동시성 직렬화 — 같은 tx_ref 의 grant/revoke 가 병렬 tx 로 겹치면 사전 SELECT 와
     * 원장 INSERT 가 별도 statement 라 REVOKE 원장과 live entitlement 가 동시에 남을 수 있다.
     * tx-scoped advisory lock 으로 토큰 단위 직렬화 — tx 종료 시 자동 해제.
     * (RecordRepository.acquireRecordDailyLock 패턴 재사용.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:txRef, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireTxRefLock(
        @Param("txRef") txRef: String,
    ): Int

    /** insert 0건 시 기존 처리 주체 확인용 — 같은 (user,key)=무해 재전송 / 다른 (user,key)=충돌. */
    fun findByTxRefAndAction(
        txRef: String,
        action: String,
    ): EntitlementTxLedger?

    /**
     * V36: tx_ref 처리 원장 insert-if-absent (UserEntitlementRepository.insertIfAbsent 패턴 승계).
     *
     * conflict target 은 uq_entitlement_tx_ledger_ref_action (tx_ref, action) 명시 — 본 테이블의
     * 유일 unique 제약이라 bare 가 아닌 명시가 안전(향후 제약 추가 시 의도 보존).
     * native ON CONFLICT DO NOTHING 이라 충돌 시 예외 없이 0 rows — tx rollback-only 오염 없음.
     *
     * @return 1 = 신규 기록(이 tx_ref 의 해당 action 최초 처리) / 0 = 이미 처리됨(멱등 신호)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO entitlement_tx_ledger (user_id, entitlement_key, tx_ref, action, processed_at)
            VALUES (:userId, :entitlementKey, :txRef, :action, NOW())
            ON CONFLICT (tx_ref, action) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("entitlementKey") entitlementKey: String,
        @Param("txRef") txRef: String,
        @Param("action") action: String,
    ): Int
}
