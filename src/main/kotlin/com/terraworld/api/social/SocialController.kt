package com.terraworld.api.social

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.constraints.Size
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.net.URI
import java.time.OffsetDateTime

@Tag(name = "Social", description = "초대 API")
@RestController
@RequestMapping("/api/v1/invites")
@Validated
class SocialController(
    private val inviteService: InviteService,
) {
    @Operation(
        summary = "초대 코드 발급",
        description = "현재 사용자가 공유할 수 있는 일회성 초대 코드를 생성. 7일 유효.",
    )
    @PostMapping
    fun createInvite(): InviteCreateResponse = inviteService.createInvite(SecurityUtil.getCurrentUserId())

    @Operation(
        summary = "초대 코드 수락",
        description = "초대받은 사용자가 링크를 통해 가입 후 수락. 초대자·수락자 각각 스페셜 코인 5개 지급.",
    )
    @PostMapping("/{code}/accept")
    fun acceptInvite(
        @PathVariable @Size(min = 4, max = 20) code: String,
    ): InviteAcceptResponsePayload = inviteService.acceptInvite(SecurityUtil.getCurrentUserId(), code)
}

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
