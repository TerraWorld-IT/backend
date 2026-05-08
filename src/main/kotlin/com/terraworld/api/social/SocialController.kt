package com.terraworld.api.social

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.SocialApi
import io.terraworld.api.model.InviteAcceptResponse
import io.terraworld.api.model.InviteResponse
import org.springframework.http.ResponseEntity
import org.springframework.validation.annotation.Validated
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SocialApi (2 endpoints — createInvite, acceptInvite) implement.
 *
 * ARCH-008-phase-10 후: InviteService 가 generated DTO 직접 반환.
 * controller 매퍼 제거. local DTO (`InviteCreateResponse`,
 * `InviteAcceptResponsePayload`, `InviteAcceptReward`) 모두 삭제.
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
    override fun createInvite(): ResponseEntity<InviteResponse> {
        val response = inviteService.createInvite(SecurityUtil.getCurrentUserId())
        return ResponseEntity.ok(response)
    }

    @Operation(
        summary = "초대 코드 수락",
        description = "초대받은 사용자가 링크를 통해 가입 후 수락. 초대자·수락자 각각 스페셜 코인 5개 지급.",
    )
    override fun acceptInvite(code: String): ResponseEntity<InviteAcceptResponse> {
        val response = inviteService.acceptInvite(SecurityUtil.getCurrentUserId(), code)
        return ResponseEntity.ok(response)
    }
}
