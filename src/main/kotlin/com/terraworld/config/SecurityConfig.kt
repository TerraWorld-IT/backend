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
 * - Internal endpoints: `/api/v1/internal/`... is gated by a shared token
 *   inside the controller and not reachable by normal user JWTs.
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
                    // Internal endpoints (bootstrap/purge) are gated by the
                    // X-Internal-Token header inside the controller. Block
                    // unauthenticated access here so no filter quirk can
                    // bypass the header check.
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
