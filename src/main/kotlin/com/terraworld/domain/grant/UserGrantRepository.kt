package com.terraworld.domain.grant

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserGrantRepository : JpaRepository<UserGrant, Long> {
    /**
     * 원자적 멱등 insert (webhook_inbox / ad_reward_nonce 패턴 승계). 동일 (user_id, idempotency_key)
     * 동시 도착 시 한 쪽만 1(inserted), 다른 쪽 0(conflict) 반환.
     *
     * check-then-insert 대신 ON CONFLICT DO NOTHING 을 쓰는 이유: `saveAndFlush` + catch
     * `DataIntegrityViolationException` 방식은 UNIQUE 위반 시 Spring 이 트랜잭션을 rollback-only 로
     * 마킹 → catch 후 정상 반환해도 커밋 시 `UnexpectedRollbackException` 발생(실버그). native
     * ON CONFLICT 는 예외를 발생시키지 않아 tx 오염이 없다.
     *
     * @return 1 = 신규 지급 / 0 = 이미 지급됨(멱등 no-op)
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO user_grants (user_id, grant_type, grant_ref, amount, idempotency_key, source, created_at)
            VALUES (:userId, :grantType, :grantRef, :amount, :idempotencyKey, :source, NOW())
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("grantType") grantType: String,
        @Param("grantRef") grantRef: String,
        @Param("amount") amount: Long,
        @Param("idempotencyKey") idempotencyKey: String,
        @Param("source") source: String,
    ): Int
}
