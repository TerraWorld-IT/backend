package com.terraworld.security

import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * Idempotently provisions a domain profile for an authenticated user.
 *
 * The primary provisioning path is the better-auth `databaseHooks.user.create.after`
 * hook (see `frontend/server/lib/auth.ts`) which calls the internal Spring
 * endpoint at signup time, before the first authenticated API request. This
 * service is also invoked as a fallback from [JwtAuthenticationFilter] so
 * that a user whose hook failed (network blip, Spring down during signup)
 * still gets provisioned lazily on their next request.
 *
 * Security invariants (SEC-003):
 *   - `role` is always forced to USER. JWT role claims are never trusted at
 *     creation time — role changes happen through a separate admin path.
 *   - The caller MUST have verified the bearer token's signature and claims
 *     before invoking this service. We do NOT re-validate identity here.
 *   - `email` must be non-blank (enforced by the filter before calling).
 *
 * Concurrency: wrapped in a Spring @Transactional. Under a concurrent
 * first-request race the loser catches DataIntegrityViolationException and
 * no-ops, which is safe because the winner fully provisioned the profile.
 */
@Service
class UserBootstrapService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val categoryRepository: CategoryRepository,
    private val terrariumRepository: TerrariumRepository,
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository,
) {
    @Transactional
    fun ensureExists(userId: String, email: String) {
        require(email.isNotBlank()) { "email must be non-blank" }
        if (userRepository.existsById(userId)) return

        val nickname = email.substringBefore('@')
            .take(50)
            .ifBlank { "user" }

        val user = try {
            userRepository.save(
                User(
                    id = userId,
                    nickname = nickname,
                    role = UserRole.USER, // SEC-003: never trust JWT role on create
                    basicCoin = 100,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // Concurrent race — another thread inserted the row. Safe to no-op
            // because that thread also provisioned the related children.
            return
        }

        // SEC-020: one-shot saveAll instead of per-row save to avoid N+1.
        val categories = categoryRepository.findAll()
        if (categories.isNotEmpty()) {
            userTokenRepository.saveAll(
                categories.map { category ->
                    UserToken(user = user, category = category, amount = 0)
                },
            )
        }

        // SEC-021: deterministic ordering — always pick the lowest-id background
        // so concurrent first-requests for different users get the same default.
        terrariumBackgroundRepository.findAll(Sort.by("id").ascending())
            .firstOrNull()
            ?.let { defaultBackground ->
                terrariumRepository.save(
                    Terrarium(user = user, background = defaultBackground),
                )
            }
    }
}
