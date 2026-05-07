package com.terraworld.api.social

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.SocialApi
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.OffsetDateTime
import io.terraworld.api.model.InviteAcceptResponse as ApiInviteAcceptResponse
import io.terraworld.api.model.InviteAcceptResponseReward as ApiInviteAcceptResponseReward
import io.terraworld.api.model.InviteResponse as ApiInviteResponse

/**
 * SocialApi (2 endpoints — createInvite, acceptInvite) implement.
 * InviteService 시그니처 (local DTO) 그대로 두고 controller 에서 generated
 * 로 변환.
 */
@Tag(name = "Social", description = "초대 API")
@RestController
@RequestMapping("/api/v1")
@Validated
class SocialController(
    private val inviteService: InviteService,
) : SocialApi {
    @Operation(
        summary = "초대 코드 발급",
        description = "현재 사용자가 공유할 수 있는 일회성 초대 코드를 생성. 7일 유효.",
    )
    override fun createInvite(): ResponseEntity<ApiInviteResponse> {
        val local = inviteService.createInvite(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(
            ApiInviteResponse(
                inviteCode = local.inviteCode,
                inviteLink = local.inviteLink,
                expiresAt = local.expiresAt,
            ),
        )
    }

    @Operation(
        summary = "초대 코드 수락",
        description = "초대받은 사용자가 링크를 통해 가입 후 수락. 초대자·수락자 각각 스페셜 코인 5개 지급.",
    )
    override fun acceptInvite(code: String): ResponseEntity<ApiInviteAcceptResponse> {
        val local = inviteService.acceptInvite(SecurityUtil.getCurrentUserId(), code)
        return ResponseEntity.ok(
            ApiInviteAcceptResponse(
                message = local.message,
                reward = ApiInviteAcceptResponseReward(specialCoins = local.reward.specialCoins),
            ),
        )
    }
}

// InviteService.createInvite / acceptInvite 의 반환 타입.
// 후속 슬라이스에서 service 가 generated DTO 를 직접 반환하도록 마이그레이션
// 후 본 local DTO 들을 제거할 예정.
data class InviteCreateResponse(
    val inviteCode: String,
    val inviteLink: URI,
    val expiresAt: OffsetDateTime,
)

data class InviteAcceptResponsePayload(
    val message: String,
    val reward: InviteAcceptReward,
)

data class InviteAcceptReward(
    val specialCoins: Int,
)
