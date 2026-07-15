package com.terraworld.security

import com.github.benmanes.caffeine.cache.Cache
import com.github.benmanes.caffeine.cache.Caffeine
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/**
 * Extracts the bearer JWT, verifies it via [JwtTokenProvider], and wires
 * the resulting [AuthenticatedUser] into the SecurityContext.
 *
 * The filter is the LAST LINE OF DEFENSE for identity, not a place to
 * execute side effects. Profile provisioning is owned by the
 * `databaseHooks.user.create.after` hook on the Nitro side (calls the
 * Spring internal bootstrap endpoint). This filter only runs
 * [UserBootstrapService.ensureExists] as a fallback — when the signup
 * hook failed and the user's first request arrives without a domain
 * profile row.
 *
 * Rejected tokens:
 *   - invalid signature / expired / missing iss/aud/exp (see JwtTokenProvider)
 *   - blank or missing `email` claim (SEC-011)
 *
 * Security note (SEC-003): this filter never grants privileges higher than
 * what the JWT declares, and the bootstrap fallback forces role=USER on
 * create. Role upgrades only propagate once the user gets a fresh JWT
 * after the domain profile's role column is updated.
 */
@Component
class JwtAuthenticationFilter(
    private val jwtTokenProvider: JwtTokenProvider,
    private val userBootstrapService: UserBootstrapService,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(JwtAuthenticationFilter::class.java)

    /**
     * BE-04 (2026-07-15 성능 감사): fallback provisioning 확인 캐시.
     *
     * 기존에는 모든 인증 요청마다 [UserBootstrapService.ensureExists] 가 SELECT(+tx) 를
     * 수행했다 — 인증 hot path 의 상시 DB 왕복. 이미 존재 확인된 userId 는 skip.
     *
     * 캐시 의미는 **"도메인 프로필 존재 확인됨" 뿐**이다 — 탈퇴 후 재가입 등으로 stale 해질
     * 수 있으나 TTL(1시간)로 자연 해소되고, miss 시 ensureExists 는 원래 멱등(existsById +
     * 동시성 race catch)이라 중복 호출도 안전하다. 공유 CacheConfig(CacheNames, TTL 10분)는
     * 정적 seed config 용이라 TTL/용도가 달라 별도 인스턴스를 사용한다 (부수효과 있는 void
     * 메서드에 @Cacheable 을 붙이는 memoization-side-effect 함정도 회피).
     */
    private val bootstrapEnsured: Cache<String, Boolean> =
        Caffeine
            .newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(100_000)
            .build()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val token = resolveToken(request)

        if (token != null && jwtTokenProvider.validateToken(token)) {
            val userId = jwtTokenProvider.getUserIdFromToken(token)
            val email = jwtTokenProvider.getEmailFromToken(token)

            if (email.isNullOrBlank()) {
                log.debug("[auth] rejecting JWT with blank email claim for sub={}", userId)
                // Leave SecurityContext empty → downstream returns 401.
            } else {
                val role = jwtTokenProvider.getRoleFromToken(token)

                // Fallback provisioning — primary path is the better-auth
                // databaseHooks.user.create.after → Spring internal endpoint
                // at signup time. This catches the rare case where the hook
                // failed (network blip, Spring unreachable at signup).
                // BE-04: 확인된 userId 는 캐시로 skip. 실패(예외) 시 캐시 미기록 —
                // 기존 시맨틱 그대로 예외는 전파(요청 실패)되고 다음 요청이 재시도한다.
                if (bootstrapEnsured.getIfPresent(userId) == null) {
                    userBootstrapService.ensureExists(userId, email)
                    bootstrapEnsured.put(userId, true)
                }

                val authorities = listOf(SimpleGrantedAuthority("ROLE_${role.name}"))
                val principal = AuthenticatedUser(id = userId, email = email)
                val auth = UsernamePasswordAuthenticationToken(principal, null, authorities)
                SecurityContextHolder.getContext().authentication = auth
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun resolveToken(request: HttpServletRequest): String? {
        val bearer = request.getHeader("Authorization") ?: return null
        return if (bearer.startsWith("Bearer ")) bearer.substring(7) else null
    }
}
