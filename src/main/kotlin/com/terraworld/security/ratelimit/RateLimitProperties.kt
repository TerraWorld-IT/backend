package com.terraworld.security.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpMethod

/**
 * Burst-protection rate limit configuration.
 *
 * Domain-level daily limits (e.g. `RewardService` 5/day, `Category.dailyLimit`)
 * are independent of this filter — they enforce business intent.
 * This filter exists to bound per-minute call volume against credential-stuffing,
 * upload spam, invite spam, etc.
 *
 * Identity for the bucket:
 *   - authenticated requests → `userId` from SecurityContext
 *   - anonymous requests     → first segment of `X-Forwarded-For` or remote IP
 */
@ConfigurationProperties(prefix = "terraworld.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val rules: List<Rule> = DEFAULT_RULES,
) {
    data class Rule(
        val method: HttpMethod,
        val path: String,
        val limit: Int,
        val windowSeconds: Long = 60,
    )

    companion object {
        // Defaults are intentionally generous — they cap clear abuse without
        // affecting normal usage. Tighten via env (TERRAWORLD_RATE_LIMIT_RULES_*).
        val DEFAULT_RULES: List<Rule> =
            listOf(
                Rule(HttpMethod.POST, "/api/v1/uploads/photo", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/exchange/special-to-basic", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/exchange/tokens", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/invites", limit = 10),
                Rule(HttpMethod.POST, "/api/v1/invites/*/accept", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/categories", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/records", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/purchases", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/rewards/attendance", limit = 5),
                Rule(HttpMethod.POST, "/api/v1/rewards/ad", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/terrarium/heart", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/terrarium/upgrade", limit = 10),
                Rule(HttpMethod.PUT, "/api/v1/terrarium/placements", limit = 60),
            )
    }
}
