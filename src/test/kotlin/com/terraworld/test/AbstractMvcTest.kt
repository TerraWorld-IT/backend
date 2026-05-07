package com.terraworld.test

import com.terraworld.security.AuthenticatedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.context.SecurityContextHolder

/**
 * `@WebMvcTest` 기반 controller smoke test 의 공통 인증 부트스트랩.
 *
 * 정책
 * - 각 테스트마다 SecurityContext 에 `AuthenticatedUser` 를 강제 주입해 controller 의
 *   `SecurityUtil.getCurrentUser()` / `getCurrentUserId()` 호출이 동작한다.
 * - JwtAuthenticationFilter / RateLimitFilter 등 실제 인증 체인은 `@WebMvcTest` 의
 *   `addFilters = false` (또는 `@AutoConfigureMockMvc(addFilters = false)`) 로
 *   비활성화하는 것을 전제 — 본 base 는 인증 결과만 흉내낸다.
 * - `@Tag("smoke")` 로 빠른 CI 서브셋 분류 가능.
 *
 * `cleanAuth` 에서 ThreadLocal 정리 → 다른 테스트 누수 방지.
 */
@Tag("smoke")
abstract class AbstractMvcTest {
    companion object {
        const val TEST_USER_ID = "test-user-1"
        const val TEST_USER_EMAIL = "test@example.com"
    }

    @BeforeEach
    fun setUpAuth() {
        val principal = AuthenticatedUser(id = TEST_USER_ID, email = TEST_USER_EMAIL)
        val auth = TestingAuthenticationToken(principal, null, emptyList())
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
    }

    @AfterEach
    fun cleanAuth() {
        SecurityContextHolder.clearContext()
    }
}
