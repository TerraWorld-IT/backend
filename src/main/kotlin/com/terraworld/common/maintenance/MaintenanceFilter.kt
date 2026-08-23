package com.terraworld.common.maintenance

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.filter.OncePerRequestFilter

/**
 * 점검 모드 필터 (N-B7, 아프젝 v2).
 *
 * `app.maintenance=true`(env `APP_MAINTENANCE`) 면 `/api/` 하위 요청에 503 + `{code:"MAINTENANCE"}` JSON 을 즉시 반환한다.
 * `/actuator/` 하위(health 등)는 제외 — 배포/로드밸런서 헬스체크는 점검 중에도 살아 있어야 한다.
 *
 * 배선: 서블릿 최상위(HIGHEST_PRECEDENCE) 가 아니라 **Spring Security 필터 체인 안, CorsFilter 바로 뒤**
 * ([com.terraworld.config.SecurityConfig] 의 addFilterAfter). 서블릿 최상위에 두면 CORS 필터보다 먼저 503 을 써서
 * 교차출처 클라이언트(dev / 앱 WebView)가 `Access-Control-Allow-Origin` 없는 응답을 받아 점검 안내 본문을 읽지 못하고
 * 프리플라이트(OPTIONS)도 503 으로 실패한다. CorsFilter 뒤에 두면 프리플라이트는 CorsFilter 가 정상 응답하고,
 * 실제 요청의 503 에는 CORS 응답 헤더가 이미 붙어 있다. 인증(JwtAuthenticationFilter)보다는 앞이라
 * 인증 여부와 무관하게 동일 응답을 준다. 빈으로 등록하지 않는다(서블릿 레벨 중복 등록 방지 — SecurityConfig 가 직접 생성).
 * 기본값 false — 평시에는 요청당 boolean 분기 1회 외 비용 없음.
 */
class MaintenanceFilter(
    private val maintenance: Boolean,
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
