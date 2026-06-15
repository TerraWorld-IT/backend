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
 *   - anonymous requests     → `request.remoteAddr` (default) or first segment of
 *                              `X-Forwarded-For` if and only if `trustForwardedFor`
 *                              is enabled (must be paired with a trusted L7 proxy).
 *
 * Failure semantics on Redis outage:
 *   - `Rule.failClosed = false` (default) → fail-OPEN (graceful degradation)
 *   - `Rule.failClosed = true` → fail-CLOSED 503 (credential / money flows)
 */
@ConfigurationProperties(prefix = "terraworld.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    /**
     * SEC-001: trust upstream `X-Forwarded-For` only when explicitly opted in.
     * MUST be paired with a trusted reverse proxy (k8s ingress / ALB / nginx)
     * that overwrites the header. Default is OFF — anonymous bucketing falls
     * back to `remoteAddr` of the connection.
     */
    val trustForwardedFor: Boolean = false,
    val rules: List<Rule> = DEFAULT_RULES,
) {
    init {
        // SEC-006: admin endpoints must not share buckets with public ones.
        // Safe-guard at boot: refuse rules that target /api/v1/admin paths.
        val adminLeak = rules.filter { it.path.startsWith("/api/v1/admin") }
        require(adminLeak.isEmpty()) {
            "Admin endpoints must not be configured as rate-limit rules: $adminLeak"
        }
    }

    data class Rule(
        val method: HttpMethod,
        val path: String,
        val limit: Int,
        val windowSeconds: Long = 60,
        /**
         * SEC-002: when `true`, Redis outage results in 503 instead of fail-open.
         * Use for credential / money flows where unbounded traffic is unacceptable.
         */
        val failClosed: Boolean = false,
    )

    companion object {
        // Defaults are intentionally generous — they cap clear abuse without
        // affecting normal usage. Tighten via env (TERRAWORLD_RATE_LIMIT_RULES_*).
        //
        // SEC-002 fail-closed: applied to currency / reward / purchase / exchange
        // / invite-create. Other read-mostly or non-money flows fail open so a
        // Redis outage does not degrade UX.
        val DEFAULT_RULES: List<Rule> =
            listOf(
                Rule(HttpMethod.POST, "/api/v1/uploads/photo", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/exchange/special-to-basic", limit = 60, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/exchange/tokens", limit = 60, failClosed = true),
                // N15: 토큰→이슬 교환 — 다른 exchange 와 동일 money flow
                Rule(HttpMethod.POST, "/api/v1/exchange/token-to-basic", limit = 60, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/invites", limit = 10, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/invites/*/accept", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/categories", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/records", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/purchases", limit = 30, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/rewards/attendance", limit = 5, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/rewards/ad", limit = 30, failClosed = true),
                // 하트 클릭은 클릭당 기본코인 +1 지급 — 매크로/자동탭 어뷰징으로 코인 인플레가
                // 생긴다. 분당 30회로 제한 (실 사용에는 충분히 관대 — 약 2초당 1회). 다른 30/min
                // 엔드포인트(uploads/categories/devices)와 동일 anti-spam 기준.
                Rule(HttpMethod.POST, "/api/v1/terrarium/heart", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/terrarium/upgrade", limit = 10, failClosed = true),
                Rule(HttpMethod.PUT, "/api/v1/terrarium/placements", limit = 60),
                // ARCH-005: device registration is a high-value abuse target
                // (FCM token enumeration, app version harvesting).
                Rule(HttpMethod.POST, "/api/v1/users/me/devices", limit = 30),
                // code-review SEC-003 (2026-05-21): Play Billing webhook 은 미인증 public
                // (X-Internal-Token 컨트롤러 게이트). money flow → failClosed. 미인증 abuse
                // (constantTimeEquals + auditService.publish 비용 유발) 를 IP 버켓으로 차단.
                Rule(HttpMethod.POST, "/api/v1/webhooks/play-billing", limit = 120, failClosed = true),
            )
    }
}
