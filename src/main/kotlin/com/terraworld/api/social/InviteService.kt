package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.UserRepository
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

    @Transactional
    fun createInvite(inviterUserId: String): InviteCreateResponse {
        userRepository.findById(inviterUserId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        val code = generateUniqueCode()
        val expiresAt = LocalDateTime.now().plusDays(EXPIRY_DAYS)
        val saved = inviteRepository.save(
            Invite(code = code, inviterUserId = inviterUserId, expiresAt = expiresAt)
        )

        return InviteCreateResponse(
            inviteCode = saved.code,
            inviteLink = URI.create("$inviteBaseUrl/${saved.code}"),
            expiresAt = saved.expiresAt.toInstant(ZoneOffset.UTC).atOffset(ZoneOffset.UTC),
        )
    }

    @Transactional
    fun acceptInvite(inviteeUserId: String, code: String): InviteAcceptResponsePayload {
        val invite = inviteRepository.findByCode(code)
            .orElseThrow { BusinessException(ErrorCode.INVITE_NOT_FOUND) }

        if (invite.isAccepted()) throw BusinessException(ErrorCode.INVITE_ALREADY_ACCEPTED)
        if (invite.isExpired()) throw BusinessException(ErrorCode.INVITE_EXPIRED)
        if (invite.inviterUserId == inviteeUserId) throw BusinessException(ErrorCode.INVITE_SELF_ACCEPT)

        val invitee = userRepository.findById(inviteeUserId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        val inviter = userRepository.findById(invite.inviterUserId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        invitee.specialCoin += ACCEPT_REWARD_SPECIAL_COINS
        inviter.specialCoin += ACCEPT_REWARD_SPECIAL_COINS
        userRepository.save(invitee)
        userRepository.save(inviter)

        invite.inviteeUserId = inviteeUserId
        invite.acceptedAt = LocalDateTime.now()
        inviteRepository.save(invite)

        return InviteAcceptResponsePayload(
            message = "초대 수락 완료: 초대자와 함께 스페셜 코인 ${ACCEPT_REWARD_SPECIAL_COINS}개씩 지급되었습니다.",
            reward = InviteAcceptReward(specialCoins = ACCEPT_REWARD_SPECIAL_COINS),
        )
    }

    private fun generateUniqueCode(): String {
        repeat(MAX_GENERATION_RETRIES) {
            val candidate = buildString(CODE_LENGTH) {
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
