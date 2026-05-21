package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.UserRepository
import io.terraworld.api.model.InviteAcceptResponse
import io.terraworld.api.model.InviteAcceptResponseReward
import io.terraworld.api.model.InviteResponse
import io.terraworld.api.model.ShareResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.ZoneOffset

@Service
class InviteService(
    private val inviteRepository: InviteRepository,
    private val userRepository: UserRepository,
    // N2 (구현 계획서 v4): invite 수락 보상 시 wallet_transactions 원장 기록
    private val walletTransactionService: com.terraworld.api.wallet.WalletTransactionService,
    // N8 (구현 계획서 v4): invite 수락 시 초대자에게 FCM 알림
    private val eventPublisher: org.springframework.context.ApplicationEventPublisher,
    // N16 (구현 계획서 v4): 공유 테라리움 조회 — 발신자 terrarium 스냅샷
    private val terrariumService: com.terraworld.api.terrarium.TerrariumService,
    @Value("\${terraworld.invite.base-url:https://terraworld.app/share}")
    private val inviteBaseUrl: String,
) {
    companion object {
        const val CODE_LENGTH = 8
        const val EXPIRY_DAYS = 7L
        const val ACCEPT_REWARD_SPECIAL_COINS = 5
        private const val CODE_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789" // 1, 0, I, O 제외
        private const val MAX_GENERATION_RETRIES = 5
    }

    private val random = SecureRandom()

    /**
     * ARCH-008-phase-10: 반환 타입을 generated [InviteResponse] 직접 사용.
     */
    @Transactional
    fun createInvite(inviterUserId: String): InviteResponse {
        userRepository
            .findById(inviterUserId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val code = generateUniqueCode()
        val expiresAt = LocalDateTime.now().plusDays(EXPIRY_DAYS)
        val saved =
            inviteRepository.save(
                Invite(code = code, inviterUserId = inviterUserId, expiresAt = expiresAt),
            )

        return InviteResponse(
            inviteCode = saved.code,
            inviteLink = URI.create("$inviteBaseUrl/${saved.code}"),
            expiresAt = saved.expiresAt.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC),
        )
    }

    /**
     * ARCH-008-phase-10: 반환 타입을 generated [InviteAcceptResponse] 직접 사용.
     */
    @Transactional
    fun acceptInvite(
        inviteeUserId: String,
        code: String,
    ): InviteAcceptResponse {
        val invite =
            inviteRepository
                .findByCode(code)
                .orElseThrow { BusinessException(ErrorCode.INVITE_NOT_FOUND) }

        if (invite.isAccepted()) throw BusinessException(ErrorCode.INVITE_ALREADY_ACCEPTED)
        if (invite.isExpired()) throw BusinessException(ErrorCode.INVITE_EXPIRED)
        if (invite.inviterUserId == inviteeUserId) throw BusinessException(ErrorCode.INVITE_SELF_ACCEPT)

        val invitee =
            userRepository
                .findById(inviteeUserId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val inviter =
            userRepository
                .findById(invite.inviterUserId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        invitee.specialCoin += ACCEPT_REWARD_SPECIAL_COINS
        inviter.specialCoin += ACCEPT_REWARD_SPECIAL_COINS
        userRepository.save(invitee)
        userRepository.save(inviter)

        invite.inviteeUserId = inviteeUserId
        invite.acceptedAt = LocalDateTime.now()
        val savedInvite = inviteRepository.save(invite)

        // N2: wallet ledger — 양쪽 SPECIAL_COIN 보상
        val specialWallet = com.terraworld.api.wallet.WalletTransactionService.CURRENCY_SPECIAL_COIN
        val inviteReason = com.terraworld.api.wallet.WalletTransactionService.REASON_INVITE_ACCEPT
        walletTransactionService.append(
            invitee,
            specialWallet,
            ACCEPT_REWARD_SPECIAL_COINS.toLong(),
            invitee.specialCoin,
            inviteReason,
            referenceId = savedInvite.id,
        )
        walletTransactionService.append(
            inviter,
            specialWallet,
            ACCEPT_REWARD_SPECIAL_COINS.toLong(),
            inviter.specialCoin,
            inviteReason,
            referenceId = savedInvite.id,
        )

        // N8: 초대자에게 FCM 알림 — invitee 가 초대를 수락했음
        eventPublisher.publishEvent(
            com.terraworld.api.notification.FriendActivityEvent(
                fromUserId = inviteeUserId,
                toUserId = invite.inviterUserId,
                message = "${invitee.nickname} 님이 초대를 수락했어요",
            ),
        )

        return InviteAcceptResponse(
            message = "초대 수락 완료: 초대자와 함께 스페셜 코인 ${ACCEPT_REWARD_SPECIAL_COINS}개씩 지급되었습니다.",
            reward = InviteAcceptResponseReward(specialCoins = ACCEPT_REWARD_SPECIAL_COINS),
        )
    }

    /**
     * N16 (구현 계획서 v4): 공유 테라리움 조회 (공개 — 인증 불요).
     * invite code 의 발신자(초대자) terrarium 스냅샷 + 닉네임 반환.
     * 만료된 코드는 INVITE_EXPIRED (410), 없는 코드는 INVITE_NOT_FOUND (404).
     */
    @Transactional(readOnly = true)
    fun getSharedTerrarium(code: String): ShareResponse {
        val invite =
            inviteRepository
                .findByCode(code)
                .orElseThrow { BusinessException(ErrorCode.INVITE_NOT_FOUND) }
        if (invite.isExpired()) throw BusinessException(ErrorCode.INVITE_EXPIRED)

        val inviter =
            userRepository
                .findById(invite.inviterUserId)
                .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val terrarium = terrariumService.getTerrarium(invite.inviterUserId)

        // N16 (Codex audit MEDIUM): 공개 share 응답에서 발신자 활동 패턴 노출 제거.
        // wilting.daysSinceRecord (최근 기록 주기) / unlockedStages (레벨 진행도) 는
        // share 코드만 있으면 누구나 볼 수 있으므로 미공개 — frontend 도 미사용.
        val publicTerrarium = terrarium.copy(wilting = null, unlockedStages = null)

        return ShareResponse(nickname = inviter.nickname, terrarium = publicTerrarium)
    }

    private fun generateUniqueCode(): String {
        repeat(MAX_GENERATION_RETRIES) {
            val candidate =
                buildString(CODE_LENGTH) {
                    repeat(CODE_LENGTH) {
                        append(CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)])
                    }
                }
            if (!inviteRepository.existsByCode(candidate)) return candidate
        }
        // 32^8 공간 내 5회 연속 충돌은 사실상 불가능. 발생 시 INTERNAL 로 처리.
        throw BusinessException(ErrorCode.INTERNAL_ERROR, "초대 코드 생성 실패")
    }
}
