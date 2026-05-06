package com.terraworld.api.userdevice

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.userdevice.DevicePlatform
import com.terraworld.domain.userdevice.UserDevice
import com.terraworld.domain.userdevice.UserDeviceRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

@Tag(name = "User", description = "푸시 디바이스 등록")
@RestController
@RequestMapping("/api/v1/users/me/devices")
class UserDeviceController(
    private val userDeviceService: UserDeviceService,
) {
    @Operation(
        summary = "푸시 디바이스 등록 (멱등)",
        description = "동일 (user, token) 재호출 시 lastSeenAt 만 갱신되며 신규 row 가 생성되지 않습니다.",
    )
    @PostMapping
    fun register(
        @Valid @RequestBody request: DeviceRegistrationRequest,
    ): DeviceRegistrationResponse {
        val userId = SecurityUtil.getCurrentUserId()
        return userDeviceService.upsert(userId, request)
    }
}

@Service
class UserDeviceService(
    private val repository: UserDeviceRepository,
    private val auditService: AuditService,
) {
    @Transactional
    fun upsert(
        userId: String,
        request: DeviceRegistrationRequest,
    ): DeviceRegistrationResponse {
        val now = LocalDateTime.now()
        val existing = repository.findByUserIdAndToken(userId, request.token)
        val platform = DevicePlatform.valueOf(request.platform)

        val saved =
            if (existing.isPresent) {
                val device = existing.get()
                device.lastSeenAt = now
                device.platform = platform
                device.isActive = true
                if (request.appVersion != null) device.appVersion = request.appVersion
                repository.save(device)
            } else {
                val device =
                    UserDevice(
                        userId = userId,
                        token = request.token,
                        platform = platform,
                        appVersion = request.appVersion,
                        lastSeenAt = now,
                        isActive = true,
                    )
                repository.save(device).also {
                    auditService.publish(
                        userId = userId,
                        action = "DEVICE_REGISTER",
                        resourceType = "UserDevice",
                        resourceId = it.id.toString(),
                        payload =
                            mapOf(
                                "platform" to platform.name,
                                "appVersion" to (request.appVersion ?: ""),
                            ),
                    )
                }
            }

        return DeviceRegistrationResponse(
            deviceId = saved.id,
            registeredAt = saved.lastSeenAt.atOffset(ZoneOffset.UTC),
        )
    }
}

@Schema(description = "디바이스 등록 요청")
data class DeviceRegistrationRequest(
    @field:NotBlank
    @field:Size(min = 1, max = 4096)
    @Schema(description = "FCM/APNs 디바이스 토큰. 동일 (user, token) 조합은 upsert 처리.")
    val token: String,
    @field:NotBlank
    @Schema(description = "ANDROID / IOS / WEB", allowableValues = ["ANDROID", "IOS", "WEB"])
    val platform: String,
    @field:Size(max = 32)
    @Schema(description = "앱 빌드 버전 (e.g. 1.4.2). 통계/타게팅 용도.")
    val appVersion: String? = null,
)

@Schema(description = "디바이스 등록 응답")
data class DeviceRegistrationResponse(
    @Schema(description = "등록된 디바이스 row 의 PK")
    val deviceId: Long,
    @Schema(description = "최초 등록 또는 갱신(upsert) 시각")
    val registeredAt: OffsetDateTime,
)
