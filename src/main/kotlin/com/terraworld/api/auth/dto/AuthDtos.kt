package com.terraworld.api.auth.dto

import io.swagger.v3.oas.annotations.media.Schema
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

@Schema(description = "회원가입 요청")
data class SignupRequest(
    @field:Email @field:NotBlank
    @Schema(example = "user@example.com")
    val email: String,

    @field:NotBlank @field:Size(min = 8)
    @Schema(example = "password123")
    val password: String,

    @field:NotBlank @field:Size(min = 2, max = 50)
    @Schema(example = "테라월드유저")
    val nickname: String,
)

@Schema(description = "로그인 요청")
data class LoginRequest(
    @field:Email @field:NotBlank
    @Schema(example = "user@example.com")
    val email: String,

    @field:NotBlank
    @Schema(example = "password123")
    val password: String,
)

@Schema(description = "토큰 갱신 요청")
data class RefreshRequest(
    @field:NotBlank
    val refreshToken: String,
)

@Schema(description = "인증 응답")
data class AuthResponse(
    val userId: Long,
    val email: String,
    val nickname: String,
    val accessToken: String,
    val refreshToken: String,
)

@Schema(description = "토큰 응답")
data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
)
