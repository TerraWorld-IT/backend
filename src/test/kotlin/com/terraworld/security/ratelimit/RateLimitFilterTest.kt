package com.terraworld.security.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.security.AuthenticatedUser
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Duration

class RateLimitFilterTest {
    private val redisTemplate: StringRedisTemplate = mock()
    private val valueOps: ValueOperations<String, String> = mock()
    private val objectMapper = ObjectMapper()

    private fun newFilter(rules: List<RateLimitProperties.Rule>): RateLimitFilter =
        RateLimitFilter(
            properties = RateLimitProperties(enabled = true, rules = rules),
            redisTemplate = redisTemplate,
            objectMapper = objectMapper,
        )

    @BeforeEach
    fun setup() {
        whenever(redisTemplate.opsForValue()).thenReturn(valueOps)
    }

    @AfterEach
    fun clearAuth() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `unmatched path passes through without touching Redis`() {
        val filter = newFilter(listOf(RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/uploads/photo", 30)))
        val request = MockHttpServletRequest("POST", "/api/v1/records")
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        verify(valueOps, never()).increment(any())
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `first call sets TTL and forwards`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5, windowSeconds = 60)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.1" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.1")).thenReturn(1L)

        filter.doFilter(request, response, chain)

        verify(redisTemplate).expire(eq("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.1"), eq(Duration.ofSeconds(60)))
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `count above limit returns 429 with Retry-After`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5, windowSeconds = 60)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.2" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.2")).thenReturn(6L)

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(429)
        assertThat(response.getHeader("Retry-After")).isEqualTo("60")
        assertThat(response.contentType).startsWith("application/json")
        assertThat(response.contentAsString).contains("RATE_LIMIT_EXCEEDED")
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `authenticated user is bucketed by userId not IP`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule))
        val auth =
            UsernamePasswordAuthenticationToken(
                AuthenticatedUser(id = "user-42", email = "u@example.com"),
                null,
                emptyList(),
            )
        SecurityContextHolder.getContext().authentication = auth

        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.3" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:u:user-42")).thenReturn(2L)

        filter.doFilter(request, response, chain)

        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites:u:user-42")
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `X-Forwarded-For first segment is used as IP`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule))
        val request =
            MockHttpServletRequest("POST", "/api/v1/invites").apply {
                addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1")
                remoteAddr = "127.0.0.1"
            }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:ip:203.0.113.5")).thenReturn(1L)

        filter.doFilter(request, response, chain)

        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites:ip:203.0.113.5")
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Redis outage fails open`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.4" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment(any())).thenThrow(DataAccessResourceFailureException("redis down"))

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `disabled property short-circuits before Redis`() {
        val filter =
            RateLimitFilter(
                properties =
                    RateLimitProperties(
                        enabled = false,
                        rules = listOf(RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", 5)),
                    ),
                redisTemplate = redisTemplate,
                objectMapper = objectMapper,
            )
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.5" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()

        filter.doFilter(request, response, chain)

        verify(valueOps, never()).increment(any())
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `Ant-style wildcard matches dynamic path segment`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites/*/accept", limit = 3)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites/ABCD1234/accept").apply { remoteAddr = "10.0.0.6" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites/*/accept:ip:10.0.0.6")).thenReturn(1L)

        filter.doFilter(request, response, chain)

        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites/*/accept:ip:10.0.0.6")
        verify(chain).doFilter(request, response)
    }
}
