package com.terraworld.domain.social

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional

interface InviteRepository : JpaRepository<Invite, Long> {
    fun findByCode(code: String): Optional<Invite>

    fun existsByCode(code: String): Boolean

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
}
