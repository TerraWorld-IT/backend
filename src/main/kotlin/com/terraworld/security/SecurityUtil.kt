package com.terraworld.security

import org.springframework.security.core.context.SecurityContextHolder

data class AuthenticatedUser(
    val id: String,
    val email: String,
)

object SecurityUtil {
    fun getCurrentUser(): AuthenticatedUser =
        SecurityContextHolder.getContext().authentication?.principal as? AuthenticatedUser
            ?: throw IllegalStateException("No authenticated user")

    fun getCurrentUserId(): String = getCurrentUser().id

    fun getCurrentEmail(): String = getCurrentUser().email
}
