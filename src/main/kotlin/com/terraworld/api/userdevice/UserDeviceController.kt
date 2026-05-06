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
    /**
     * 디바이스 토큰 멱등 upsert.
     *
     * Audit 정책 (ARCH-004): INSERT 만 `DEVICE_REGISTER` 발행, 동일 (user, token)
     * 재호출은 lastSeenAt/appVersion 만 갱신하므로 audit 발행 안 함. 토큰 owner 가
     * 다른 user 로 바뀌는 경우(SEC-003) 는 별도 `DEVICE_TOKEN_REASSIGN` 발행.
     *
     * Cross-user 재할당 (SEC-003): FCM/APNs 토큰은 device-scoped 이므로 한 토큰을
     * 동시에 두 user 가 보유할 수 없다. 신규 INSERT 직전 다른 user 의 active row
     * 가 있으면 모두 `isActive=false` 로 마킹한다.
     */
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
                // SEC-003: 다른 user 가 같은 토큰을 active 로 들고 있으면 deactivate.
                val foreignActive =
                    repository
                        .findAllByTokenAndIsActiveTrue(request.token)
                        .filter { it.userId != userId }
                if (foreignActive.isNotEmpty()) {
                    foreignActive.forEach { row ->
                        row.isActive = false
                        repository.save(row)
                        auditService.publish(
                            userId = row.userId,
                            action = "DEVICE_TOKEN_REASSIGN",
                            resourceType = "UserDevice",
                            resourceId = row.id.toString(),
                            payload =
                                mapOf(
                                    "reassignedTo" to userId,
                                    "platform" to row.platform.name,
                                ),
                        )
                    }
                }

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
