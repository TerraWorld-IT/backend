package com.terraworld.common.maintenance

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 점검 모드 필터 (N-B7, 아프젝 v2).
 *
 * `app.maintenance=true`(env `APP_MAINTENANCE`) 면 `/api/` 하위 요청에 503 + `{code:"MAINTENANCE"}` JSON 을 즉시 반환한다.
 * `/actuator/` 하위(health 등)는 제외 — 배포/로드밸런서 헬스체크는 점검 중에도 살아 있어야 한다.
 * Spring Security 보다 앞(HIGHEST_PRECEDENCE)에서 동작해 인증 여부와 무관하게 동일 응답을 준다.
 * 기본값 false — 평시에는 요청당 boolean 분기 1회 외 비용 없음.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class MaintenanceFilter(
    @Value("\${app.maintenance:false}") private val maintenance: Boolean,
) : OncePerRequestFilter() {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun shouldNotFilter(request: HttpServletRequest): Boolean = !maintenance || !request.requestURI.startsWith(API_PREFIX)

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        log.debug("maintenance.reject method={} uri={}", request.method, request.requestURI)
        response.status = HttpStatus.SERVICE_UNAVAILABLE.value()
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.setHeader("Retry-After", RETRY_AFTER_SECONDS)
        response.writer.write(BODY)
    }

    companion object {
        private const val API_PREFIX = "/api/"
        private const val RETRY_AFTER_SECONDS = "300"
        private const val BODY = """{"code":"MAINTENANCE","message":"서비스 점검 중입니다. 잠시 후 다시 시도해 주세요"}"""
    }
}
