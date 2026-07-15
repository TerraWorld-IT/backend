package com.terraworld.security.ratelimit

import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.security.AuthenticatedUser
import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.http.HttpMethod
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * BE-15 (2026-07-15 성능 감사): INCR+EXPIRE 2-call → 단일 Lua 스크립트 원자화에 맞춰
 * stubbing 을 `redisTemplate.execute(script, keys, windowSeconds)` 로 갱신.
 * 검증 시맨틱(rule 매칭 / subject 버킷 / 429 경계 / fail-closed·open)은 기존과 동일.
 *
 * R4-RL (2026-07-15 적대적 리뷰): EXPIRE 조건이 `count == 1` → `TTL < 0` 검사로 변경 —
 * 비원자 시절 생성된 TTL-less 키 치유. execute 시그니처는 동일하므로 stub 은 불변,
 * 스크립트 시맨틱은 아래 텍스트 pin 테스트로 회귀 잠금.
 */
class RateLimitFilterTest {
    private val redisTemplate: StringRedisTemplate = mock()
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

    /** 지정 key 의 원자 INCR+EXPIRE 스크립트 실행이 count 를 반환하도록 stub. */
    private fun stubCount(
        key: String,
        count: Long,
    ) {
        whenever(redisTemplate.execute(any<RedisScript<Long>>(), eq(listOf(key)), any())).thenReturn(count)
    }

    private fun verifyIncremented(key: String) {
        verify(redisTemplate).execute(any<RedisScript<Long>>(), eq(listOf(key)), any())
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
        verifyNoInteractions(redisTemplate)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `first call runs atomic INCR+EXPIRE script with window seconds and forwards`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5, windowSeconds = 60)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.1" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        stubCount("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.1", 1L)

        filter.doFilter(request, response, chain)

        // BE-15: EXPIRE 는 스크립트 내부에서 원자 실행 — windowSeconds 가 ARGV 로 전달된다.
        verify(redisTemplate).execute(
            any<RedisScript<Long>>(),
            eq(listOf("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.1")),
            eq("60"),
        )
        verify(chain).doFilter(request, response)
    }

    @Test
    fun `count above limit returns 429 with Retry-After`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites", limit = 5, windowSeconds = 60)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = "10.0.0.2" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        stubCount("terraworld:rl:POST:/api/v1/invites:ip:10.0.0.2", 6L)

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
        stubCount("terraworld:rl:POST:/api/v1/invites:u:user-42", 2L)

        filter.doFilter(request, response, chain)

        verifyIncremented("terraworld:rl:POST:/api/v1/invites:u:user-42")
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
        stubCount("terraworld:rl:POST:/api/v1/invites:ip:203.0.113.5", 1L)

        filter.doFilter(request, response, chain)

        verifyIncremented("terraworld:rl:POST:/api/v1/invites:ip:203.0.113.5")
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
        stubCount("terraworld:rl:POST:/api/v1/invites:ip:127.0.0.1", 1L)

        filter.doFilter(request, response, chain)

        verifyIncremented("terraworld:rl:POST:/api/v1/invites:ip:127.0.0.1")
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
        whenever(redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any()))
            .thenThrow(DataAccessResourceFailureException("redis down"))

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
        whenever(redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any()))
            .thenThrow(DataAccessResourceFailureException("redis down"))

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
        stubCount("terraworld:rl:POST:/api/v1/invites:u:$sharedString", 1L)
        filter.doFilter(authedReq, MockHttpServletResponse(), mock())

        // 2) 익명 IP 사용자가 같은 문자열로 진입 — 다른 버킷 키로 INCR 되어야 함
        SecurityContextHolder.clearContext()
        val anonReq = MockHttpServletRequest("POST", "/api/v1/invites").apply { remoteAddr = sharedString }
        stubCount("terraworld:rl:POST:/api/v1/invites:ip:$sharedString", 1L)
        filter.doFilter(anonReq, MockHttpServletResponse(), mock())

        // u: prefix 와 ip: prefix 는 별도 스크립트 실행 (키 분리)
        verifyIncremented("terraworld:rl:POST:/api/v1/invites:u:$sharedString")
        verifyIncremented("terraworld:rl:POST:/api/v1/invites:ip:$sharedString")
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
        whenever(redisTemplate.execute(any<RedisScript<Long>>(), any<List<String>>(), any()))
            .thenThrow(DataAccessResourceFailureException("redis down"))

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

        verifyNoInteractions(redisTemplate)
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
        stubCount("terraworld:rl:POST:/api/v1/terrarium/heart:u:user-heart", 31L)

        filter.doFilter(request, response, chain)

        assertThat(response.status).isEqualTo(429)
        assertThat(response.contentAsString).contains("RATE_LIMIT_EXCEEDED")
        verify(chain, never()).doFilter(any(), any())
    }

    @Test
    fun `heart endpoint — 30번째 클릭(한도 경계)은 통과(forward)`() {
        // pass 경계 잠금: count == limit(30) 은 통과해야 한다 (차단은 count > limit).
        // `count > limit` → `count >= limit` off-by-one 회귀 시 30번째가 silent block 되는 것을 검출.
        val filter = newFilter(RateLimitProperties.DEFAULT_RULES)
        val auth =
            UsernamePasswordAuthenticationToken(
                AuthenticatedUser(id = "user-heart2", email = "u2@example.com"),
                null,
                emptyList(),
            )
        SecurityContextHolder.getContext().authentication = auth

        val request = MockHttpServletRequest("POST", "/api/v1/terrarium/heart").apply { remoteAddr = "10.0.0.10" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        stubCount("terraworld:rl:POST:/api/v1/terrarium/heart:u:user-heart2", 30L)

        filter.doFilter(request, response, chain)

        verify(chain).doFilter(request, response)
        assertThat(response.status).isEqualTo(200)
    }

    @Test
    fun `heart rule 은 money flow 라 failClosed=true (코인 발행 — Redis 장애 시 503)`() {
        val heartRule =
            RateLimitProperties.DEFAULT_RULES.single {
                it.method == HttpMethod.POST && it.path == "/api/v1/terrarium/heart"
            }
        assertThat(heartRule.failClosed).isTrue()
    }

    @Test
    fun `R4-RL — Lua 스크립트는 TTL-less 키를 치유한다 (count==1 조건이 아닌 TTL 음수 검사)`() {
        // 회귀 pin: EXPIRE 를 `count == 1` 조건부로 되돌리면 비원자 시절(2-call) 생성된
        // TTL-less 키는 count 가 1 로 돌아오지 않아 영구 429 (window 리셋 없는 self-DoS).
        // Lua 는 단위 테스트에서 실행 불가 — 스크립트 소스를 텍스트로 잠근다.
        val field = RateLimitFilter::class.java.getDeclaredField("INCR_WITH_EXPIRE_SCRIPT")
        field.isAccessible = true
        val script = (field.get(null) as RedisScript<*>).scriptAsString
        assertThat(script).contains("redis.call('TTL', KEYS[1]) < 0")
        assertThat(script).doesNotContain("count == 1")
    }

    @Test
    fun `Ant-style wildcard matches dynamic path segment`() {
        val rule = RateLimitProperties.Rule(HttpMethod.POST, "/api/v1/invites/*/accept", limit = 3)
        val filter = newFilter(listOf(rule))
        val request = MockHttpServletRequest("POST", "/api/v1/invites/ABCD1234/accept").apply { remoteAddr = "10.0.0.6" }
        val response = MockHttpServletResponse()
        val chain: FilterChain = mock()
        stubCount("terraworld:rl:POST:/api/v1/invites/*/accept:ip:10.0.0.6", 1L)

        filter.doFilter(request, response, chain)

        verifyIncremented("terraworld:rl:POST:/api/v1/invites/*/accept:ip:10.0.0.6")
        verify(chain).doFilter(request, response)
    }
}
