package com.terraworld.api.user

import com.terraworld.api.userdevice.UserDeviceService
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.UserApi
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.terraworld.api.user.dto.CategoryTokenAmount as LocalCategoryTokenAmount
import com.terraworld.api.user.dto.CurrencyResponse as LocalCurrencyResponse
import com.terraworld.api.user.dto.EntitlementsResponse as LocalEntitlementsResponse
import com.terraworld.api.user.dto.PlacedItemResponse as LocalPlacedItemResponse
import com.terraworld.api.user.dto.ProgressResponse as LocalProgressResponse
import com.terraworld.api.user.dto.UserMeResponse as LocalUserMeResponse
import com.terraworld.api.userdevice.DeviceRegistrationRequest as LocalDeviceRegistrationRequest
import io.terraworld.api.model.CategoryTokenAmount as ApiCategoryTokenAmount
import io.terraworld.api.model.CurrencyResponse as ApiCurrencyResponse
import io.terraworld.api.model.DeviceRegistrationRequest as ApiDeviceRegistrationRequest
import io.terraworld.api.model.DeviceRegistrationResponse as ApiDeviceRegistrationResponse
import io.terraworld.api.model.EntitlementsResponse as ApiEntitlementsResponse
import io.terraworld.api.model.PlacedItemResponse as ApiPlacedItemResponse
import io.terraworld.api.model.ProgressResponse as ApiProgressResponse
import io.terraworld.api.model.UserMeResponse as ApiUserMeResponse

/**
 * 첫 generated `*Api` 인터페이스 채택 슬라이스 (User 도메인 통합).
 *
 * - `UserApi` 를 implement → spec 변경 시 컴파일 fail 로 드리프트 가드
 * - `getMe` + `registerDevice` 둘 다 본 클래스가 처리 (spec 의 tags=[User]
 *   기준 generator 그룹핑)
 * - `UserService` / `UserDeviceService` 시그니처는 그대로 두고, controller
 *   에서 generated DTO ↔ local DTO 인라인 mapper 만 추가. spec 에 새 필드가
 *   추가되면 mapper 의 `required` 인자가 누락되어 컴파일 안 됨 → gate 효과.
 * - `@RequestMapping("/api/v1")` 으로 prefix 만 잡고 endpoint path 는
 *   `UserApi.PATH_GET_ME` ("/users/me"), `UserApi.PATH_REGISTER_DEVICE`
 *   ("/users/me/devices") 가 결정한다.
 */
@Tag(name = "User", description = "유저 / 푸시 디바이스 등록 API")
@RestController
@RequestMapping("/api/v1")
class UserController(
    private val userService: UserService,
    private val userDeviceService: UserDeviceService,
) : UserApi {
    @Operation(summary = "내 정보 조회", description = "현재 로그인한 유저의 전체 데이터를 반환합니다")
    override fun getMe(): ResponseEntity<ApiUserMeResponse> {
        val current = SecurityUtil.getCurrentUser()
        val local = userService.getMe(current.id, current.email)
        return ResponseEntity.ok(local.toApi())
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

    private fun LocalUserMeResponse.toApi(): ApiUserMeResponse =
        ApiUserMeResponse(
            userId = userId,
            email = email,
            nickname = nickname,
            role = ApiUserMeResponse.Role.forValue(role),
            currency = currency.toApi(),
            progress = progress.toApi(),
            ownedItems = ownedItems,
            placedItems = placedItems.map { it.toApi() },
            entitlements = entitlements.toApi(),
        )

    private fun LocalCurrencyResponse.toApi(): ApiCurrencyResponse =
        ApiCurrencyResponse(
            basicCoins = basicCoins,
            specialCoins = specialCoins,
            walkTokens = walkTokens,
            readTokens = readTokens,
            runTokens = runTokens,
            drawTokens = drawTokens,
            categoryTokens = categoryTokens.map { it.toApi() },
        )

    private fun LocalCategoryTokenAmount.toApi(): ApiCategoryTokenAmount =
        ApiCategoryTokenAmount(
            categoryId = categoryId,
            categoryName = categoryName,
            amount = amount,
        )

    private fun LocalProgressResponse.toApi(): ApiProgressResponse =
        ApiProgressResponse(
            level = level,
            experience = experience,
            experienceToNext = experienceToNext,
        )

    private fun LocalPlacedItemResponse.toApi(): ApiPlacedItemResponse =
        ApiPlacedItemResponse(
            itemId = itemId,
            itemSlug = itemSlug,
            slotId = slotId,
        )

    private fun LocalEntitlementsResponse.toApi(): ApiEntitlementsResponse =
        ApiEntitlementsResponse(
            freePlacement = freePlacement,
            premiumThemes = premiumThemes,
        )
}
