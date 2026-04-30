package com.terraworld.domain.social

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface InviteRepository : JpaRepository<Invite, Long> {
    fun findByCode(code: String): Optional<Invite>

    fun existsByCode(code: String): Boolean
}
