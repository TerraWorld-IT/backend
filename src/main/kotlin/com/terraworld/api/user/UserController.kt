package com.terraworld.api.user

import com.terraworld.api.user.dto.UserMeResponse
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "User", description = "유저 API")
@RestController
@RequestMapping("/api/v1/users")
class UserController(
    private val userService: UserService,
) {

    @Operation(summary = "내 정보 조회", description = "현재 로그인한 유저의 전체 데이터를 반환합니다")
    @GetMapping("/me")
    fun getMe(): UserMeResponse {
        val current = SecurityUtil.getCurrentUser()
        return userService.getMe(current.id, current.email)
    }
}
