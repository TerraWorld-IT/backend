package com.terraworld.domain.user

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface UserRepository : JpaRepository<User, String>

interface UserTokenRepository : JpaRepository<UserToken, Long> {
    fun findByUserIdAndCategoryId(userId: String, categoryId: Long): Optional<UserToken>
    fun findAllByUserId(userId: String): List<UserToken>
}
