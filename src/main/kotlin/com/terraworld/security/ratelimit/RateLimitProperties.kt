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
        // SEC-002 fail-closed 적용 대상: 재화 / 보상 / 구매 / 환전 / 초대생성 / 초대수락.
        // 그 외 읽기 위주·비재화 흐름은 fail-open — Redis 장애가 UX 를 저하시키지 않도록.
        val DEFAULT_RULES: List<Rule> =
            listOf(
                Rule(HttpMethod.POST, "/api/v1/uploads/photo", limit = 30),
                // H1: 통합 교환 엔드포인트 — money flow → failClosed. 삭제된 구 경로
                // (special-to-basic / tokens / token-to-basic) 를 대체.
                Rule(HttpMethod.POST, "/api/v1/exchange", limit = 60, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/invites", limit = 10, failClosed = true),
                // R5: 초대 수락은 양측 RUBY 지급 reward flow → failClosed (다른 money/reward 룰과 통일).
                //   Redis 장애 시 accept rate 가 무제한 되는 것 차단. (claimAccept 원자성이 동일 invite
                //   중복 지급은 막지만, rate-limit 은 volume 어뷰징을 막는 별개 레이어.)
                Rule(HttpMethod.POST, "/api/v1/invites/*/accept", limit = 30, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/categories", limit = 30),
                Rule(HttpMethod.POST, "/api/v1/records", limit = 60),
                Rule(HttpMethod.POST, "/api/v1/purchases", limit = 30, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/rewards/attendance", limit = 5, failClosed = true),
                Rule(HttpMethod.POST, "/api/v1/rewards/ad", limit = 30, failClosed = true),
                // 하트 클릭은 클릭당 기본코인 +1 지급 — 매크로/자동탭 어뷰징으로 코인 인플레가
                // 생긴다. 분당 30회로 제한 (실 사용에는 충분히 관대 — 약 2초당 1회).
                // SEC-002: 클릭당 코인을 mint 하는 money flow 이므로 failClosed=true.
                // (rewards/ad 등 다른 보상 지급 룰과 동일) — Redis 장애 시 fail-open 으로
                // 무제한 코인 farming 되는 것을 차단. 도메인 일일 cap 부재라 더욱 중요.
                Rule(HttpMethod.POST, "/api/v1/terrarium/heart", limit = 30, failClosed = true),
                // H1: 테라리움 티어 업그레이드 — money flow → failClosed. 삭제된 구
                // 경로 (/terrarium/upgrade) 를 대체.
                Rule(HttpMethod.POST, "/api/v1/terrarium/tier", limit = 10, failClosed = true),
                Rule(HttpMethod.PUT, "/api/v1/terrarium/placements", limit = 60),
                // H1: 습관 체크인 — 재화 지급이 도메인 일일 cap 으로 bound 되어 fail-open.
                Rule(HttpMethod.POST, "/api/v1/habits/*/checkin", limit = 30),
                // H1: 키우기 부스터 — 재화 소비/지급 money flow → failClosed.
                Rule(HttpMethod.POST, "/api/v1/growth/*/booster", limit = 30, failClosed = true),
                // H1: IAP 영수증 검증 — 결제 money flow → failClosed.
                Rule(HttpMethod.POST, "/api/v1/billing/iap/verify", limit = 30, failClosed = true),
                // ARCH-005: device registration is a high-value abuse target
                // (FCM token enumeration, app version harvesting).
                Rule(HttpMethod.POST, "/api/v1/users/me/devices", limit = 30),
                // code-review SEC-003 (2026-05-21): Play Billing webhook 은 미인증 public
                // (X-Internal-Token 컨트롤러 게이트). money flow → failClosed. 미인증 abuse
                // (constantTimeEquals + auditService.publish 비용 유발) 를 IP 버켓으로 차단.
                Rule(HttpMethod.POST, "/api/v1/webhooks/play-billing", limit = 120, failClosed = true),
                // R6 (2026-07-02): RTDN Pub/Sub push ingestion 경로. 형제 /play-billing 과
                // 대칭 — 미인증 public(/webhooks/** permitAll)이며 호출당 constantTimeEquals +
                // (토큰 통과 시) Play API resolveObfuscatedAccountId + entitlement grant/revoke +
                // Discord alert 비용 유발. AntPathMatcher 리터럴 패턴은 prefix 매칭이 아니라
                // /play-billing 룰이 더 긴 /play-billing/pubsub 을 커버하지 못한다 → 별도 룰 필수.
                // money flow → failClosed. 정상 Pub/Sub push 는 저빈도라 120/min 로 저해 없음.
                Rule(HttpMethod.POST, "/api/v1/webhooks/play-billing/pubsub", limit = 120, failClosed = true),
            )
    }
}
