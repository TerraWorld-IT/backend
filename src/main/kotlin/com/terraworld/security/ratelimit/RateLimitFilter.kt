package com.terraworld.security.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.util.AntPathMatcher
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/**
 * Burst-protection rate limiter (per-window, fixed-bucket) backed by Redis.
 *
 * Key shape: `terraworld:rl:{METHOD}:{rulePathPattern}:{subjectId}`
 *
 * On Redis outage the filter fails open with a WARN log (graceful
 * degradation) — domain-level daily limits in services still apply.
 *
 * Filter chain ordering:
 *   The bean is added in [com.terraworld.config.SecurityConfig] AFTER
 *   [com.terraworld.security.JwtAuthenticationFilter] so userId can be
 *   read from SecurityContext when present.
 */
@Component
class RateLimitFilter(
    private val properties: RateLimitProperties,
    private val redisTemplate: StringRedisTemplate,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(RateLimitFilter::class.java)
    private val pathMatcher = AntPathMatcher()

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (!properties.enabled) {
            filterChain.doFilter(request, response)
            return
        }

        val method = HttpMethod.valueOf(request.method)
        val path = request.requestURI
        val rule = matchRule(method, path)
        if (rule == null) {
            filterChain.doFilter(request, response)
            return
        }

        val subject = resolveSubject(request)
        val key = "terraworld:rl:${rule.method.name()}:${rule.path}:$subject"

        val count =
            try {
                val incremented = redisTemplate.opsForValue().increment(key) ?: 0L
                if (incremented == 1L) {
                    redisTemplate.expire(key, Duration.ofSeconds(rule.windowSeconds))
                }
                incremented
            } catch (e: DataAccessException) {
                log.warn("[rate-limit] Redis unavailable, failing open: {}", e.message)
                filterChain.doFilter(request, response)
                return
            }

        if (count > rule.limit) {
            writeRateLimitedResponse(response, rule)
            return
        }

        filterChain.doFilter(request, response)
    }

    private fun matchRule(
        method: HttpMethod,
        path: String,
    ): RateLimitProperties.Rule? =
        properties.rules.firstOrNull {
            it.method == method && pathMatcher.match(it.path, path)
        }

    private fun resolveSubject(request: HttpServletRequest): String {
        val auth = SecurityContextHolder.getContext().authentication
        val principal = auth?.principal
        if (principal is com.terraworld.security.AuthenticatedUser) {
            return "u:${principal.id}"
        }
        val xff = request.getHeader("X-Forwarded-For")
        val ip =
            if (!xff.isNullOrBlank()) {
                xff.split(",").first().trim()
            } else {
                request.remoteAddr ?: "unknown"
            }
        return "ip:$ip"
    }

    private fun writeRateLimitedResponse(
        response: HttpServletResponse,
        rule: RateLimitProperties.Rule,
    ) {
        response.status = HttpStatus.TOO_MANY_REQUESTS.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader(HttpHeaders.RETRY_AFTER, rule.windowSeconds.toString())
        val body =
            mapOf(
                "code" to "RATE_LIMIT_EXCEEDED",
                "message" to "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요",
            )
        response.writer.write(objectMapper.writeValueAsString(body))
    }
}
