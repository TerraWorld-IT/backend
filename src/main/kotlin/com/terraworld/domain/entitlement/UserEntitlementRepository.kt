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

    fun deleteByIdUserIdAndIdEntitlementKey(
        userId: String,
        entitlementKey: String,
    ): Long

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
