package com.terraworld.domain.social

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime
import java.util.Optional

interface InviteRepository : JpaRepository<Invite, Long> {
    fun findByCode(code: String): Optional<Invite>

    fun existsByCode(code: String): Boolean

    /**
     * H3: 초대 수락의 원자적 claim — read-check(acceptedAt==null)-save 사이 race 로 인한 이중 보상 방지.
     * 조건부 UPDATE(acceptedAt IS NULL)로 "미수락 → 수락" 전이를 단일 statement 로 직렬화한다.
     * 동시 수락 시 한 쪽만 rows==1(claim 성공), 다른 쪽은 rows==0(이미 수락됨).
     * @return 1 = claim 성공(→ 양측 RUBY credit) / 0 = 이미 수락됨(→ credit 안 함)
     */
    @Modifying(clearAutomatically = true)
    @Query(
        """
        UPDATE Invite i
        SET i.inviteeUserId = :invitee, i.acceptedAt = :now
        WHERE i.id = :id AND i.acceptedAt IS NULL
        """,
    )
    fun claimAccept(
        @Param("id") id: Long,
        @Param("invitee") inviteeUserId: String,
        @Param("now") now: LocalDateTime,
    ): Int

    /**
     * 두 사용자가 수락된 invite 관계인지 확인 (방향 무관). joint-record 검증에 사용된다.
     * inviter→invitee 또는 invitee→inviter 어느 방향이든 관계 성립.
     */
    @Query(
        """
        SELECT COUNT(i) > 0 FROM Invite i
        WHERE i.acceptedAt IS NOT NULL
          AND (
            (i.inviterUserId = :a AND i.inviteeUserId = :b)
            OR (i.inviterUserId = :b AND i.inviteeUserId = :a)
          )
        """,
    )
    fun existsAcceptedBetween(
        @Param("a") userIdA: String,
        @Param("b") userIdB: String,
    ): Boolean

    /**
     * 두 사용자 간 수락된 invite (방향 무관) — 친구 함께 습관의 연동 ID(invite.id) 해석에 사용.
     * 친구 관계 = 수락된 invite (별도 friendship 테이블 불요).
     */
    @Query(
        """
        SELECT i FROM Invite i
        WHERE i.acceptedAt IS NOT NULL
          AND (
            (i.inviterUserId = :a AND i.inviteeUserId = :b)
            OR (i.inviterUserId = :b AND i.inviteeUserId = :a)
          )
        ORDER BY i.acceptedAt DESC
        LIMIT 1
        """,
    )
    fun findAcceptedBetween(
        @Param("a") userIdA: String,
        @Param("b") userIdB: String,
    ): Optional<Invite>

    /**
     * P-FRIEND-001 (구현 계획서 v4): 사용자가 관여한 수락된 invite 전체 (친구 목록 SoT).
     * inviter 또는 invitee 어느 쪽이든. 친구 관계 = 수락된 invite (별도 friendship 테이블 불요).
     */
    @Query(
        """
        SELECT i FROM Invite i
        WHERE i.acceptedAt IS NOT NULL
          AND (i.inviterUserId = :userId OR i.inviteeUserId = :userId)
        """,
    )
    fun findAcceptedInvolvingUser(
        @Param("userId") userId: String,
    ): List<Invite>

    /**
     * R10 INVITE-RUBY-SYBIL-FARM: RUBY(유료 화폐)는 pair 당 1회만 지급 — Sybil 2계정이 서로 무제한 distinct
     * 코드를 발급·수락해 farming 하는 것 차단. existsAcceptedBetween(방향무관) read-check 는 서로 다른 코드의
     * 동시 수락에 TOCTOU 이므로, per-pair(정렬 키) tx-scoped advisory lock 으로 check+claim+credit 을 직렬화.
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireInvitePairLock(
        @Param("key") key: String,
    ): Int
}
