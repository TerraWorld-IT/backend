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

    private fun newFilter(
        rules: List<RateLimitProperties.Rule>,
        trustForwardedFor: Boolean = false,
    ): RateLimitFilter =
        RateLimitFilter(
            properties = RateLimitProperties(enabled = true, rules = rules, trustForwardedFor = trustForwardedFor),
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
    fun `SEC-001 — trustForwardedFor=true 일 때만 X-Forwarded-For 첫 segment 사용`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule), trustForwardedFor = true)
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
    fun `SEC-001 — trustForwardedFor=false (default) 면 XFF 무시 + remoteAddr 사용`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule), trustForwardedFor = false)
        val request =
            MockHttpServletRequest("POST", "/api/v1/invites").apply {
                addHeader("X-Forwarded-For", "203.0.113.5, 10.0.0.1") // attacker-controlled
                remoteAddr = "127.0.0.1"
            }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:ip:127.0.0.1")).thenReturn(1L)

        filter.doFilter(request, response, chain)

        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites:ip:127.0.0.1")
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `SEC-002 — failClosed=true rule 은 Redis 장애 시 503 반환`() {
        val rule =
            RateLimitProperties.Rule(
                HttpMethod.POST,
                "/api/v1/exchange/tokens",
                limit = 60,
                failClosed = true,
            )
        val filter = newFilter(listOf(rule))
        val request =
            MockHttpServletRequest("POST", "/api/v1/exchange/tokens").apply { remoteAddr = "10.0.0.7" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment(any())).thenThrow(DataAccessResourceFailureException("redis down"))

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(503)
        assertThat(response.contentAsString).contains("DEPENDENCY_UNAVAILABLE")
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `SEC-002 — failClosed=false rule 은 Redis 장애 시 fail-open (기존 동작 보장)`() {
        val rule =
            RateLimitProperties.Rule(
                HttpMethod.POST,
                "/api/v1/records",
                limit = 60,
                failClosed = false,
            )
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/records").apply { remoteAddr = "10.0.0.8" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        whenever(valueOps.increment(any())).thenThrow(DataAccessResourceFailureException("redis down"))

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `SEC-010 — u_userId 와 ip_userId 는 동일 문자열이어도 버킷 충돌 안 함`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5)
        val filter = newFilter(listOf(rule))
        val sharedString = "203.0.113.5"

        // 1) 인증된 user-id 가 우연히 IP 문자열과 같음
        val auth =
            UsernamePasswordAuthenticationToken(
                AuthenticatedUser(id = sharedString, email = "u@example.com"),
                null,
                emptyList(),
            )
        SecurityContextHolder.getContext().authentication = auth
        val authedReq = MockHttpServletRequest("POST", "/api/v1/invites")
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:u:$sharedString")).thenReturn(1L)
        filter.doFilter(authedReq, MockHttpServletResponse(), mock())

        // 2) 익명 IP 사용자가 같은 문자열로 진입 — 다른 버킷 키로 INCR 되어야 함
        SecurityContextHolder.clearContext()
        val anonReq = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = sharedString }
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/invites:ip:$sharedString")).thenReturn(1L)
        filter.doFilter(anonReq, MockHttpServletResponse(), mock())

        // u: prefix 와 ip: prefix 는 별도 INCR 호출
        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites:u:$sharedString")
        verify(valueOps).increment("terraworld:rl:POST:/api/v1/invites:ip:$sharedString")
    }

    @Test
    fun `SEC-006 — admin path rule 은 boot 시 거부`() {
        val ex =
            kotlin
                .runCatching {
                    RateLimitProperties(
                        enabled = true,
                        rules = listOf(RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/admin/items", 10)),
                    )
                }.exceptionOrNull()
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex?.message).contains("admin")
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
    fun `heart endpoint is per-user rate-limited by DEFAULT_RULES (limit=30) — 31번째 클릭은 429`() {
        // 기존 Redis rate-limit 인프라 재사용: DEFAULT_RULES 안에 heart rule 이 등록돼
        // 있어 별도 필터/애너테이션 없이 분당 30회로 제한된다 (anti-spam, 코인 인플레 차단).
        val heartRule =
            RateLimitProperties.DEFAULT_RULES.single {
                it.method == HttpMethod.POST && it.path == "/api/v1/terrarium/heart"
            }
        assertThat(heartRule.limit).isEqualTo(30)

        val filter = newFilter(RateLimitProperties.DEFAULT_RULES)
        val auth =
            UsernamePasswordAuthenticationToken(
                AuthenticatedUser(id = "user-heart", email = "u@example.com"),
                null,
                emptyList(),
            )
        SecurityContextHolder.getContext().authentication = auth

        val request = MockHttpServletRequest("POST", "/api/v1/terrarium/heart").apply { remoteAddr = "10.0.0.9" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        // 한도(30) 를 넘긴 31번째 클릭 — count > limit 이므로 차단.
        whenever(valueOps.increment("terraworld:rl:POST:/api/v1/terrarium/heart:u:user-heart")).thenReturn(31L)

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(429)
        assertThat(response.contentAsString).contains("RATE_LIMIT_EXCEEDED")
        verify(chain, never()).doFilter(any(), any())
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
