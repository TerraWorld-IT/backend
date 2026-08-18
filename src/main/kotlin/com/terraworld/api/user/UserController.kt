package com.terraworld.api.user

import com.terraworld.api.userdevice.UserDeviceService
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.UserApi
import io.terraworld.api.model.UpdateMeRequest
import io.terraworld.api.model.UserMeResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.userdevice.DeviceRegistrationRequest as LocalDeviceRegistrationRequest
import io.terraworld.api.model.DeviceRegistrationRequest as ApiDeviceRegistrationRequest
import io.terraworld.api.model.DeviceRegistrationResponse as ApiDeviceRegistrationResponse

/**
 * UserApi (`getMe` + `registerDevice`) implement.
 *
 * ARCH-008-phase-13 후: UserService 가 generated [UserMeResponse] 직접 반환 — controller 매퍼 0.
 * UserDevice DeviceRegistrationResponse 는 별도 도메인 service 라 그대로 유지 (UserDevice phase 별도).
 */
@Tag(name = "User", description = "유저 / 푸시 디바이스 등록 API")
@RestController
@RequestMapping("/api/v1")
class UserController(
    private val userService: UserService,
    private val userDeviceService: UserDeviceService,
) : UserApi {
    @Operation(summary = "내 정보 조회", description = "현재 로그인한 유저의 전체 데이터를 반환합니다")
    override fun getMe(): ResponseEntity<UserMeResponse> {
        val current = SecurityUtil.getCurrentUser()
        val response = userService.getMe(current.id, current.email)
        return ResponseEntity.ok(response)
    }

    @Operation(summary = "내 프로필 수정", description = "닉네임 갱신 후 getMe 와 동일한 유저 전체 스냅샷을 반환합니다")
    override fun updateMe(
        @Valid updateMeRequest: UpdateMeRequest,
    ): ResponseEntity<UserMeResponse> {
        val current = SecurityUtil.getCurrentUser()
        return ResponseEntity.ok(userService.updateMe(current.id, current.email, updateMeRequest.nickname))
    }

    @Operation(
        summary = "푸시 디바이스 등록 (멱등)",
        description = "동일 (user, token) 재호출 시 lastSeenAt 만 갱신되며 신규 row 가 생성되지 않습니다.",
    )
    override fun registerDevice(
        deviceRegistrationRequest: ApiDeviceRegistrationRequest,
    ): ResponseEntity<ApiDeviceRegistrationResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val localResp =
            userDeviceService.upsert(
                userId = userId,
                request =
                    LocalDeviceRegistrationRequest(
                        token = deviceRegistrationRequest.token,
                        platform = deviceRegistrationRequest.platform.value,
                        appVersion = deviceRegistrationRequest.appVersion,
                    ),
            )
        return ResponseEntity.ok(
            ApiDeviceRegistrationResponse(
                deviceId = localResp.deviceId,
                registeredAt = localResp.registeredAt,
            ),
        )
    }
}
