package com.terraworld.api.social

import com.terraworld.api.terrarium.TerrariumService
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.social.TerrariumLike
import com.terraworld.domain.social.TerrariumLikeRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.TerrariumResponse
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * P-FRIEND-001 (구현 계획서 v4, 2026-05-21): 친구 시스템 — 목록 / 놀러가기 / 좋아요.
 *
 * 친구 관계 SoT = 수락된 invite (`Invite.acceptedAt IS NOT NULL`, inviter/invitee 양방향).
 * 별도 friendship 테이블 불요 — 기존 모델 재사용 (Scope Discipline).
 */
@Service
class FriendService(
    private val inviteRepository: InviteRepository,
    private val userRepository: UserRepository,
    private val terrariumLikeRepository: TerrariumLikeRepository,
    private val terrariumService: TerrariumService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 친구 목록 — 수락된 invite 의 상대방 userId + nickname. */
    @Transactional(readOnly = true)
    fun listFriends(userId: String): List<FriendInfo> {
        val friendIds =
            inviteRepository
                .findAcceptedInvolvingUser(userId)
                .mapNotNull { invite ->
                    if (invite.inviterUserId == userId) invite.inviteeUserId else invite.inviterUserId
                }.distinct()
        if (friendIds.isEmpty()) return emptyList()
        // BE-10 (2026-07-15 성능 감사): 친구당 countByTargetUserId N+1 → IN + GROUP BY 1쿼리.
        // like 0건인 친구는 집계 결과에 없으므로 0 default (기존 count=0 semantics 동일).
        val likeCountByTarget =
            terrariumLikeRepository
                .countGroupedByTargetUserIdIn(friendIds)
                .associate { it.targetUserId to it.likeCount }
        val likedTargets =
            terrariumLikeRepository
                .findAllByLikerUserIdAndTargetUserIdIn(userId, friendIds)
                .map { it.targetUserId }
                .toSet()
        return userRepository
            .findAllById(friendIds)
            .map { user ->
                FriendInfo(
                    userId = user.id,
                    nickname = user.nickname,
                    likeCount = likeCountByTarget[user.id] ?: 0L,
                    liked = user.id in likedTargets,
                )
            }
    }

    /** 놀러가기 — 친구 테라리움 조회 (친구 관계 검증 후). */
    @Transactional(readOnly = true)
    fun visitFriendTerrarium(
        userId: String,
        friendId: String,
    ): TerrariumResponse {
        if (!inviteRepository.existsAcceptedBetween(userId, friendId)) {
            throw BusinessException(ErrorCode.FORBIDDEN, "친구가 아닌 사용자의 테라리움입니다")
        }
        return terrariumService.getTerrarium(friendId)
    }

    /** 좋아요 토글 — 이미 했으면 취소, 아니면 추가. 친구 관계 + 본인 아님 검증. */
    @Transactional
    fun toggleLike(
        userId: String,
        targetUserId: String,
    ): LikeResult {
        if (userId == targetUserId) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "본인 테라리움은 좋아요할 수 없습니다")
        }
        if (!inviteRepository.existsAcceptedBetween(userId, targetUserId)) {
            throw BusinessException(ErrorCode.FORBIDDEN, "친구가 아닌 사용자입니다")
        }
        val existing = terrariumLikeRepository.findByLikerUserIdAndTargetUserId(userId, targetUserId)
        val liked: Boolean
        if (existing.isPresent) {
            terrariumLikeRepository.delete(existing.get())
            liked = false
        } else {
            // code-review CDX-003 (2026-05-21): check-then-insert race.
            // `uq_terrarium_like` UNIQUE 제약이 **정합성을 보장** (동시 좋아요여도 row 1개).
            // 동시 요청의 패자는 DataIntegrityViolationException → GlobalExceptionHandler 가
            // 400 → 클라이언트 재시도 시 정상 (멱등). try/catch 로 같은 @Transactional 안에서
            // 삼키면 tx 가 rollback-only 로 오염되므로(UnexpectedRollbackException) 잡지 않고
            // 전파한다 — like 더블탭은 드문 edge, 정합성 손실 없음.
            terrariumLikeRepository.save(
                TerrariumLike(likerUserId = userId, targetUserId = targetUserId),
            )
            liked = true
        }
        val count = terrariumLikeRepository.countByTargetUserId(targetUserId)
        log.info("friend.like user={} target={} liked={} count={}", userId, targetUserId, liked, count)
        return LikeResult(liked = liked, likeCount = count)
    }
}

data class FriendInfo(
    val userId: String,
    val nickname: String,
    val likeCount: Long,
    val liked: Boolean,
)

data class LikeResult(
    val liked: Boolean,
    val likeCount: Long,
)
