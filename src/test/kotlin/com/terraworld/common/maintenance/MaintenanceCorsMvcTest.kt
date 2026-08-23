package com.terraworld.common.maintenance

import com.terraworld.config.SecurityConfig
import com.terraworld.security.JwtAuthenticationFilter
import com.terraworld.security.ratelimit.RateLimitFilter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.RestController

/**
 * 점검 모드 × CORS — Security 필터 체인 전체(MockMvc addFilters=true)로 배선을 검증한다.
 *
 * MaintenanceFilter 가 체인 안 CorsFilter 뒤에 있으므로:
 *  - 허용 Origin 의 실제 요청(GET) → 503 MAINTENANCE 본문 + `Access-Control-Allow-Origin` (교차출처 FE 가 본문을 읽을 수 있다)
 *  - 프리플라이트(OPTIONS) → CorsFilter 가 응답(200 + CORS 헤더). 503 이 아니라 브라우저가 실제 요청을 이어 보낸다.
 *  - 비허용 Origin 의 프리플라이트 → CorsFilter 가 403 (점검과 무관하게 CORS 정책 유지)
 *  - actuator 는 503 대상 아님.
 * JwtAuthenticationFilter / RateLimitFilter 는 MockBean — 503/프리플라이트 경로는 그 앞에서 끝나므로 호출되지 않는다.
 */
@WebMvcTest(
    // 컨트롤러를 비우면 전체 @Controller 가 스캔돼 서비스 의존성이 필요해진다 — 매핑 없는 프로브 컨트롤러 하나만 둔다.
    controllers = [MaintenanceCorsMvcTest.ProbeController::class],
    properties = [
        "app.maintenance=true",
        "cors.allowed-origins=http://localhost:3000",
    ],
)
@Import(SecurityConfig::class)
class MaintenanceCorsMvcTest {
    /** 매핑 없는 프로브 — @WebMvcTest 의 controllers 슬라이스를 이 클래스 하나로 한정하기 위한 자리표시자. */
    @RestController
    class ProbeController

    @Autowired private lateinit var mockMvc: MockMvc

    @MockBean private lateinit var jwtAuthenticationFilter: JwtAuthenticationFilter

    @MockBean private lateinit var rateLimitFilter: RateLimitFilter

    @Test
    fun `maintenance on — 허용 Origin 의 GET 은 503 MAINTENANCE + Access-Control-Allow-Origin`() {
        mockMvc
            .perform(get("/api/v1/terrarium").header(HttpHeaders.ORIGIN, "http://localhost:3000"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
            .andExpect(header().string("Retry-After", "300"))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"MAINTENANCE\"")))
    }

    @Test
    fun `maintenance on — 허용 Origin 의 프리플라이트(OPTIONS)는 CorsFilter 가 200 + CORS 헤더로 응답 (503 아님)`() {
        mockMvc
            .perform(
                options("/api/v1/terrarium")
                    .header(HttpHeaders.ORIGIN, "http://localhost:3000")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Authorization"),
            ).andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:3000"))
            .andExpect(header().exists(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS))
    }

    @Test
    fun `maintenance on — 비허용 Origin 의 프리플라이트는 CORS 403 (점검과 무관하게 정책 유지)`() {
        mockMvc
            .perform(
                options("/api/v1/terrarium")
                    .header(HttpHeaders.ORIGIN, "https://evil.example")
                    .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET"),
            ).andExpect(status().isForbidden)
    }

    @Test
    fun `maintenance on — Origin 없는 same-origin GET 도 503 MAINTENANCE`() {
        mockMvc
            .perform(get("/api/v1/habits"))
            .andExpect(status().isServiceUnavailable)
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"code\":\"MAINTENANCE\"")))
    }
}
