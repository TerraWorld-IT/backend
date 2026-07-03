package com.terraworld.security

import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import java.text.Normalizer
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
    // 낙서장 P1: 초기 잔액 seed = 신 substrate 단일 SoT
    private val currencyService: com.terraworld.api.currency.CurrencyService,
    private val terrariumRepository: TerrariumRepository,
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository,
) {
    @Transactional
    fun ensureExists(
        userId: String,
        email: String,
        nickname: String? = null,
    ) {
        require(email.isNotBlank()) { "email must be non-blank" }
        if (userRepository.existsById(userId)) return

        // 닉네임 정규화(Codex auth review): NFC + 제어/포맷(Bidi 포함, \p{Cc}\p{Cf}) 제거 + trim + 50자 절단.
        // 부재/blank 시 email prefix(PII) 대신 non-PII 기본값(userId 뒷 4자 — 랜덤 id, PII 아님).
        val cleanNickname =
            nickname
                ?.let { Normalizer.normalize(it, Normalizer.Form.NFC) }
                ?.replace(Regex("[\\p{Cc}\\p{Cf}]"), "")
                ?.trim()
                ?.take(50)
                ?.ifBlank { null }
        val resolvedNickname = cleanNickname ?: "테라주민${userId.takeLast(4)}"

        val user =
            try {
                userRepository.save(
                    User(
                        id = userId,
                        nickname = resolvedNickname,
                        role = UserRole.USER, // SEC-003: never trust JWT role on create
                    ),
                )
            } catch (e: DataIntegrityViolationException) {
                // Concurrent race — another thread inserted the row. Safe to no-op
                // because that thread also provisioned the related children.
                return
            }

        // 낙서장 P1: 초기 잔액 seed — 신 substrate 단일 SoT (COIN=100, RUBY=10). 구 basicCoin/specialCoin·user_tokens 제거(V32).
        currencyService.credit(userId, com.terraworld.domain.currency.CurrencyCode.COIN, 100, "BOOTSTRAP")
        currencyService.credit(userId, com.terraworld.domain.currency.CurrencyCode.RUBY, 10, "BOOTSTRAP")

        // SEC-021: deterministic ordering — always pick the lowest-id background
        // so concurrent first-requests for different users get the same default.
        terrariumBackgroundRepository
            .findAll(Sort.by("id").ascending())
            .firstOrNull()
            ?.let { defaultBackground ->
                terrariumRepository.save(
                    Terrarium(user = user, background = defaultBackground),
                )
            }
    }
}
