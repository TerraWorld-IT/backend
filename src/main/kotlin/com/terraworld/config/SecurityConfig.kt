package com.terraworld.config

import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

/**
 * Spring Security wiring for the TerraWorld backend.
 *
 * - Authentication: stateless; JWTs are validated by [JwtAuthenticationFilter].
 * - CSRF: disabled (no session cookies) — relies on Bearer tokens.
 * - CORS: profile-scoped allow-list. Production does NOT trust localhost.
 * - permitAll 경로(의도된 설계): `/api/v1/internal/...`, `/api/v1/webhooks/...`,
 *   `/api/v1/rewards/ad/ssv-callback` 은 Spring 레이어에서 인증을 요구하지 않는다.
 *   각 경로의 실 인증 게이트는 컨트롤러 안에 있다 —
 *   internal 은 X-Internal-Token 공유 시크릿(constant-time 비교), webhooks 는 공유 토큰,
 *   SSV 콜백은 ECDSA 서명검증.
 *   webhooks 와 SSV 콜백은 Google Pub/Sub(Play RTDN) 과 Google AdMob 이 인터넷에서 직접
 *   호출하므로 공개가 정상이며, 호출자가 스스로를 인증한다.
 *   `/api/v1/internal/` 의 네트워크 레벨 심층방어(외부 트래픽 차단)는 리버스 프록시 몫이다.
 * - Swagger / API docs: public in non-prod, locked down in prod.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig(
    private val jwtAuthenticationFilter: JwtAuthenticationFilter,
    private val rateLimitFilter: RateLimitFilter,
    private val environment: Environment,
    @Value("\${cors.allowed-origins}") private val allowedOrigins: List<String>,
) {
    private val isProd: Boolean
        get() = environment.activeProfiles.any { it.equals("prod", ignoreCase = true) }

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .cors { it.configurationSource(corsConfigurationSource()) }
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests { auth ->
                auth
                    // ARCH-02 (2026-07-29 주석 정정): 아래 permitAll 은 **의도된 설계**이며
                    // Spring Security 는 이 경로를 전혀 차단하지 않는다 (이전 주석의
                    // "Block unauthenticated access here" 서술은 코드와 반대였음).
                    // 실 인증 게이트는 오직 X-Internal-Token 공유 시크릿 하나 —
                    // InternalUserController.isTokenValid 의 MessageDigest.isEqual
                    // (constant-time) 비교. 로그인 세션/JWT 는 이 경로와 무관하다.
                    // 네트워크 레벨 심층방어(외부에서 /api/v1/internal/ 도달 자체를 막는 것)는
                    // 리버스 프록시가 담당해야 하며, 여기서 제공되지 않는다.
                    .requestMatchers("/api/v1/internal/**")
                    .permitAll()
                    // code-review SEC-001/CDX-001 (HIGH, 2026-05-21): Play Billing RTDN
                    // webhook 은 Bearer JWT 가 아닌 X-Internal-Token 헤더로 호출된다.
                    // permitAll 누락 시 Spring Security 가 컨트롤러의 constant-time 토큰
                    // 검증에 도달하기 전 401 → 결제 grant/revoke webhook 전면 무력화.
                    // 실 인증 게이트는 PlayBillingWebhookController 의 토큰 검증.
                    .requestMatchers("/api/v1/webhooks/**")
                    .permitAll()
                    // WP-3 (2026-06-04): AdMob SSV callback 은 Google 서버가 무인증 GET 호출.
                    // 실 인증 게이트는 AdSsvCallbackController 의 ECDSA 서명검증 레이어.
                    // POST /api/v1/rewards/ad (client nonce) 는 인증 유지 — ssv-callback 만 permit.
                    .requestMatchers("/api/v1/rewards/ad/ssv-callback")
                    .permitAll()
                    // Auth (sign-in / sign-up / session) is owned by better-auth
                    // on the Nuxt/Nitro side. Spring only validates bearer JWTs
                    // for protected domain endpoints.
                    .requestMatchers("/api/v1/share/**")
                    .permitAll()
                    // GET 목록만 공개(미인증 시 시스템 4종). POST create / 그 외 메서드는
                    // anyRequest().authenticated() 로 fall-through — 컨트롤러 util-throw(500) 대신
                    // 인증 레이어에서 401 (review MEDIUM: method-scope 누락 경계 결함).
                    .requestMatchers(HttpMethod.GET, "/api/v1/categories")
                    .permitAll()
                    .requestMatchers("/api/v1/items/**")
                    .permitAll()
                    .apply {
                        // SEC-023: Swagger UI and API docs exposed only outside prod.
                        if (!isProd) {
                            requestMatchers(
                                "/api-docs/**",
                                "/swagger-ui/**",
                                "/v3/api-docs/**",
                            ).permitAll()
                        }
                    }.requestMatchers("/actuator/health")
                    .permitAll()
                    .requestMatchers("/api/v1/admin/**")
                    .hasRole("ADMIN")
                    .anyRequest()
                    .authenticated()
            }
            // 필터 레벨 인증/인가 실패 응답 표준화 (fuzz F-2/F-3):
            //  - 미인증(Bearer 없음/무효) → 401 (403 아님) + JSON body + WWW-Authenticate.
            //  - 인증됐으나 권한 부족(예: 비-ADMIN 이 /admin) → 403 + JSON body.
            // (컨트롤러 내부에서 throw 되는 AccessDeniedException 은 GlobalExceptionHandler 담당.)
            .exceptionHandling { ex ->
                ex.authenticationEntryPoint { _, response, _ ->
                    response.setHeader("WWW-Authenticate", "Bearer")
                    writeJsonError(response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHORIZED", "인증이 필요합니다")
                }
                ex.accessDeniedHandler { _, response, _ ->
                    writeJsonError(response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "접근 권한이 없습니다")
                }
            }.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter::class.java)
            // RateLimitFilter sits AFTER JWT extraction so authenticated subjects
            // are bucketed by userId rather than IP. On Redis outage it fails open.
            .addFilterAfter(rateLimitFilter, JwtAuthenticationFilter::class.java)

        return http.build()
    }

    /** 필터 레벨 인증/인가 실패 시 일관된 JSON 오류 바디 (Content-Type 명시). */
    private fun writeJsonError(
        response: HttpServletResponse,
        status: Int,
        code: String,
        message: String,
    ) {
        response.status = status
        response.contentType = "application/json"
        response.characterEncoding = "UTF-8"
        response.writer.write("""{"code":"$code","message":"$message"}""")
    }

    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource {
        require(allowedOrigins.isNotEmpty()) {
            "cors.allowed-origins must be configured (see application.yml). " +
                "Do not leave this empty in any profile."
        }
        // SEC-009: localhost origins are only acceptable in non-prod profiles.
        if (isProd) {
            val localhostLeaks = allowedOrigins.filter { it.contains("localhost", ignoreCase = true) }
            require(localhostLeaks.isEmpty()) {
                "cors.allowed-origins contains localhost entries in prod profile: $localhostLeaks"
            }
        }

        val configuration =
            CorsConfiguration().apply {
                this.allowedOrigins = this@SecurityConfig.allowedOrigins
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // SEC-009: explicit header allow-list instead of wildcard (the wildcard
                // is incompatible with allowCredentials=true in the CORS spec).
                allowedHeaders =
                    listOf(
                        "Authorization",
                        "Content-Type",
                        "Accept",
                        "Accept-Language",
                        "X-Requested-With",
                        "X-Idempotency-Key",
                        // openapi.ts 401 재시도 마커 — 미허용 시 교차출처(dev) 환경에서 재시도
                        // preflight 가 거부돼 401 복구가 통째로 실패한다 (2026-07-20 시각검증 실측.
                        // 프로덕션은 same-origin 프록시라 잠복했던 갭).
                        "x-tw-retried",
                    )
                exposedHeaders = listOf("X-Request-Id")
                allowCredentials = true
                maxAge = 3600L
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", configuration)
        }
    }
}
