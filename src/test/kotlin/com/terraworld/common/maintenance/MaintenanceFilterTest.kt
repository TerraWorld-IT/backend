package com.terraworld.common.maintenance

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MaintenanceFilter (N-B7): app.maintenance=true 면 /api/ 하위 503 MAINTENANCE JSON, actuator 는 통과, false 면 전부 통과.
 * 체인 배선(CorsFilter 뒤 — 503 에 CORS 헤더)은 [MaintenanceCorsMvcTest] 가 Security 체인 전체로 검증한다.
 */
class MaintenanceFilterTest {
    @Test
    fun `maintenance on — api 요청은 503 + code MAINTENANCE, 체인 미진행`() {
        val filter = MaintenanceFilter(maintenance = true)
        val req = MockHttpServletRequest("GET", "/api/v1/terrarium")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(req, res, chain)
        assertEquals(503, res.status)
        assertTrue(res.contentAsString.contains("\"code\":\"MAINTENANCE\""))
        assertEquals(null, chain.request) // 체인으로 넘어가지 않음
    }

    @Test
    fun `maintenance on — actuator health 는 통과`() {
        val filter = MaintenanceFilter(maintenance = true)
        val req = MockHttpServletRequest("GET", "/actuator/health")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(req, res, chain)
        assertEquals(200, res.status)
        assertTrue(chain.request != null)
    }

    @Test
    fun `maintenance off — api 요청 통과`() {
        val filter = MaintenanceFilter(maintenance = false)
        val req = MockHttpServletRequest("POST", "/api/v1/habits")
        val res = MockHttpServletResponse()
        val chain = MockFilterChain()
        filter.doFilter(req, res, chain)
        assertEquals(200, res.status)
        assertTrue(chain.request != null)
    }
}
