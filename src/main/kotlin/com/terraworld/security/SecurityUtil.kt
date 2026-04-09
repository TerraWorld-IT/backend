package com.terraworld.security

import org.springframework.security.core.context.SecurityContextHolder

object SecurityUtil {
    fun getCurrentUserId(): Long =
        SecurityContextHolder.getContext().authentication?.principal as? Long
            ?: throw IllegalStateException("No authenticated user")
}
