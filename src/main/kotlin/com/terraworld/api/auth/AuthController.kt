package com.terraworld.api.auth

import com.terraworld.api.auth.dto.*
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/api/v1/auth")
class AuthController(
    private val authService: AuthService,
) {

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    fun signup(@Valid @RequestBody request: SignupRequest): AuthResponse =
        authService.signup(request)

    @Operation(summary = "로그인")
    @PostMapping("/login")
    fun login(@Valid @RequestBody request: LoginRequest): AuthResponse =
        authService.login(request)

    @Operation(summary = "토큰 갱신")
    @PostMapping("/refresh")
    fun refresh(@Valid @RequestBody request: RefreshRequest): TokenResponse =
        authService.refresh(request)

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    fun logout(): Map<String, String> {
        authService.logout()
        return mapOf("message" to "로그아웃 되었습니다")
    }
}
