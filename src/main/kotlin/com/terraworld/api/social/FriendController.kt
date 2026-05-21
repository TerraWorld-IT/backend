package com.terraworld.api.social

import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Hidden
import io.terraworld.api.model.TerrariumResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * P-FRIEND-001 (구현 계획서 v4, 2026-05-21): 친구 시스템 endpoint.
 *
 * 친구 목록 / 놀러가기 (친구 테라리움 조회) / 좋아요 토글. 친구 관계는 수락된 invite.
 *
 * @Hidden — OpenAPI spec 미포함 (internal). frontend 는 raw authed fetch 로 호출.
 * 경로는 `/api/v1/social/friends` — `/share` 가 아니므로 SecurityConfig 의
 * `anyRequest().authenticated()` 로 JWT 인증 강제됨 (친구 기능은 인증 필요).
 */
@RestController
@RequestMapping("/api/v1/social/friends")
@Hidden
class FriendController(
    private val friendService: FriendService,
) {
    @GetMapping
    fun listFriends(): ResponseEntity<List<FriendInfo>> = ResponseEntity.ok(friendService.listFriends(SecurityUtil.getCurrentUserId()))

    @GetMapping("/{friendId}/terrarium")
    fun visitFriendTerrarium(
        @PathVariable friendId: String,
    ): ResponseEntity<TerrariumResponse> =
        ResponseEntity.ok(
            friendService.visitFriendTerrarium(SecurityUtil.getCurrentUserId(), friendId),
        )

    @PostMapping("/{friendId}/like")
    fun toggleLike(
        @PathVariable friendId: String,
    ): ResponseEntity<LikeResult> =
        ResponseEntity.ok(
            friendService.toggleLike(SecurityUtil.getCurrentUserId(), friendId),
        )
}
