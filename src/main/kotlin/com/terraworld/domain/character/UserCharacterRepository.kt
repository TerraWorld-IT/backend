package com.terraworld.domain.character

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface UserCharacterRepository : JpaRepository<UserCharacter, Long> {
    fun findAllByUserId(userId: String): List<UserCharacter>

    /**
     * 원자적 멱등 지급 (ON CONFLICT DO NOTHING) — 중복 소유 방지. rollback-only 오염 없음.
     * @return 1 = 신규 소유 / 0 = 이미 보유
     */
    @Modifying
    @Query(
        value =
            """
            INSERT INTO user_characters (user_id, character_code, acquired_via, acquired_at)
            VALUES (:userId, :characterCode, :acquiredVia, NOW())
            ON CONFLICT (user_id, character_code) DO NOTHING
            """,
        nativeQuery = true,
    )
    fun insertIfAbsent(
        @Param("userId") userId: String,
        @Param("characterCode") characterCode: String,
        @Param("acquiredVia") acquiredVia: String,
    ): Int
}
