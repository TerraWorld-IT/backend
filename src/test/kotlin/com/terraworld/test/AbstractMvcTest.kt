package com.terraworld.test

import com.terraworld.security.AuthenticatedUser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Tag
import org.springframework.security.authentication.TestingAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
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
 * SF-008 가드
 * - `setUpAuth` 진입부에 `clearContext()` 를 한 번 더 호출 — JUnit lifecycle
 *   이 누락된 edge case (이전 test method 에서 예외 발생) 에서도 stale auth
 *   누수를 방지한다.
 * - `junit-platform.properties` 가 parallel 실행을 비활성 (`same_thread`)
 *   — 추가 안전망.
 *
 * SEC-101 가드
 * - `authorities` 인자를 받도록 helper 확장. 기본값은 `ROLE_USER` 부여.
 *   향후 `@PreAuthorize("hasRole('ADMIN')")` endpoint smoke test 시
 *   `injectAuth(listOf(SimpleGrantedAuthority("ROLE_ADMIN")))` 로 대체 가능.
 */
@Tag("smoke")
abstract class AbstractMvcTest {
    companion object {
        const val TEST_USER_ID = "test-user-1"
        const val TEST_USER_EMAIL = "test@example.com"
        val DEFAULT_USER_AUTHORITIES = listOf(SimpleGrantedAuthority("ROLE_USER"))
    }

    @BeforeEach
    fun setUpAuth() {
        // 이전 test method 의 cleanAuth 가 누락된 edge case 도 방어.
        SecurityContextHolder.clearContext()
        injectAuth(DEFAULT_USER_AUTHORITIES)
    }

    @AfterEach
    fun cleanAuth() {
        SecurityContextHolder.clearContext()
    }

    /**
     * 테스트 도중 권한을 변경할 때 호출 (예: ADMIN endpoint smoke).
     * [setUpAuth] 가 기본적으로 ROLE_USER 를 주입한다.
     */
    protected fun injectAuth(authorities: Collection<SimpleGrantedAuthority> = DEFAULT_USER_AUTHORITIES) {
        val principal = AuthenticatedUser(id = TEST_USER_ID, email = TEST_USER_EMAIL)
        val auth = TestingAuthenticationToken(principal, null, authorities.toList())
        auth.isAuthenticated = true
        SecurityContextHolder.getContext().authentication = auth
    }
}
