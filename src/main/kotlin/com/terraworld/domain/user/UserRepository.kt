package com.terraworld.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserRepository : JpaRepository<User, Long> {
    fun findByEmail(email: String): Optional<User>
    fun existsByEmail(email: String): Boolean
}

interface UserTokenRepository : JpaRepository<UserToken, Long> {
    fun findByUserIdAndCategoryId(userId: Long, categoryId: Long): Optional<UserToken>
    fun findAllByUserId(userId: Long): List<UserToken>
}
