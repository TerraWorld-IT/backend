package com.terraworld.security

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

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
                userBootstrapService.ensureExists(userId, email)

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
