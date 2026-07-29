package com.terraworld.api.internal

import com.terraworld.security.UserBootstrapService
import jakarta.annotation.PostConstruct
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.AccessDeniedException
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Internal endpoints called by the Nuxt/Nitro side at auth lifecycle events.
 *
 * The better-auth `databaseHooks.user.create.after` hook invokes
 * [bootstrap] after a successful signup so that Spring's domain profile
 * is provisioned atomically with the auth identity.
 *
 * Secured by a shared internal token (`INTERNAL_API_TOKEN`) rather than a
 * user JWT — there is no logged-in user at signup time. The token is
 * transferred out-of-band and scoped narrowly: the only endpoints under
 * ``/api/v1/internal/...`` rely on it. ARCH-02 (2026-07-29 주석 정정): SecurityConfig
 * 는 이 경로를 차단하지 않는다 — `/api/v1/internal/...` 는 의도적으로 permitAll 이며,
 * 실 인증 게이트는 아래 [isTokenValid] 의 상수시간 토큰 비교 하나뿐이다.
 * 인터넷에서 이 경로에 도달하는 것 자체를 막는 심층방어는 리버스 프록시 몫이다.
 *
 * NOT added to the OpenAPI spec on purpose — these are infra endpoints,
 * not customer-facing.
 */
@RestController
@RequestMapping("/api/v1/internal/users")
class InternalUserController(
    private val userBootstrapService: UserBootstrapService,
    @Value("\${auth.internal.token}") private val expectedToken: String,
) {
    private val log = LoggerFactory.getLogger(InternalUserController::class.java)

    /**
     * SEC-031: a blank `INTERNAL_API_TOKEN` is a configuration error.
     * Spring's prod profile will fail to start because `${INTERNAL_API_TOKEN}`
     * has no default; in dev the empty fallback would make every signup's
     * databaseHook → bootstrap call return 403 silently. Surface that
     * loudly at boot.
     */
    @PostConstruct
    fun warnIfTokenBlank() {
        if (expectedToken.isBlank()) {
            log.warn(
                "[auth] INTERNAL_API_TOKEN is blank — every better-auth " +
                    "databaseHook bootstrap call will be rejected with 403. " +
                    "Set INTERNAL_API_TOKEN in .env (must match the frontend value).",
            )
        }
    }

    @PostMapping("/bootstrap")
    fun bootstrap(
        @RequestHeader("X-Internal-Token") token: String?,
        @Valid @RequestBody body: BootstrapRequest,
    ): ResponseEntity<Unit> {
        if (!isTokenValid(token)) {
            throw AccessDeniedException("invalid internal token")
        }
        userBootstrapService.ensureExists(body.userId, body.email, body.nickname)
        return ResponseEntity.status(HttpStatus.CREATED).build()
    }

    /**
     * SEC-026: constant-time comparison so a network-positioned attacker
     * cannot side-channel the token byte-by-byte via response-time
     * differences. `MessageDigest.isEqual` runs in O(min(a,b)) regardless
     * of where the first mismatch is.
     */
    private fun isTokenValid(presented: String?): Boolean {
        if (presented.isNullOrBlank() || expectedToken.isBlank()) return false
        val a = presented.toByteArray(StandardCharsets.UTF_8)
        val b = expectedToken.toByteArray(StandardCharsets.UTF_8)
        return MessageDigest.isEqual(a, b)
    }
}

data class BootstrapRequest(
    @field:NotBlank @field:Size(max = 128)
    val userId: String,
    @field:NotBlank @field:Email @field:Size(max = 255)
    val email: String,
    // 가입 시 입력한 닉네임(better-auth user.name). 부재/blank 시 서비스가 non-PII 기본값 적용.
    // tolerant 한도(200) — 초과분은 service 가 정규화+50자 절단(400 거부 대신 절단 → email-prefix fallback 경로 회피).
    @field:Size(max = 200)
    val nickname: String? = null,
)
