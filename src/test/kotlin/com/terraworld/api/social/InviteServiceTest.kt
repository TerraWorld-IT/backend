package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * InviteService 의 핵심 분기를 검증한다. Mockito 대신 [FakeJpaRepository] 기반
 * in-memory fake 를 사용해 Spring context 없이 밀리초 단위로 돈다.
 *
 * 커버리지: 초대 발급 / 자기 초대 거부 / 만료 / 중복 수락 / 코드 미존재 /
 *          해피 패스 + existsAcceptedBetween 양 방향 검증.
 */
class InviteServiceTest {
    private lateinit var inviteRepo: FakeInviteRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var service: InviteService

    @BeforeEach
    fun setup() {
        inviteRepo = FakeInviteRepository()
        userRepo = FakeUserRepository()
        service = InviteService(inviteRepo, userRepo, "https://terraworld.app/share")

        userRepo.save(User(id = "inviter-1", nickname = "테스터1"))
        userRepo.save(User(id = "invitee-1", nickname = "테스터2"))
    }

    @Test
    fun `createInvite 는 8자 유니크 코드와 7일 만료 초대를 반환한다`() {
        val response = service.createInvite("inviter-1")

        assertEquals(InviteService.CODE_LENGTH, response.inviteCode.length)
        assertTrue(response.inviteLink.toString().endsWith(response.inviteCode))
        assertNotNull(response.expiresAt)
        assertEquals(1, inviteRepo.all().size)
    }

    @Test
    fun `acceptInvite — 본인 초대 수락 거부`() {
        val created = service.createInvite("inviter-1")

        val ex =
            assertThrows<BusinessException> {
                service.acceptInvite("inviter-1", created.inviteCode)
            }
        assertEquals(ErrorCode.INVITE_SELF_ACCEPT, ex.errorCode)
    }

    @Test
    fun `acceptInvite — 만료된 초대 거부`() {
        val expired =
            inviteRepo.save(
                Invite(
                    code = "EXPIRED01",
                    inviterUserId = "inviter-1",
                    expiresAt = LocalDateTime.now().minusDays(1),
                ),
            )

        val ex =
            assertThrows<BusinessException> {
                service.acceptInvite("invitee-1", expired.code)
            }
        assertEquals(ErrorCode.INVITE_EXPIRED, ex.errorCode)
    }

    @Test
    fun `acceptInvite — 이미 수락된 초대 거부`() {
        val used =
            inviteRepo.save(
                Invite(
                    code = "USED0001",
                    inviterUserId = "inviter-1",
                    expiresAt = LocalDateTime.now().plusDays(1),
                    acceptedAt = LocalDateTime.now().minusHours(1),
                ),
            )

        val ex =
            assertThrows<BusinessException> {
                service.acceptInvite("invitee-1", used.code)
            }
        assertEquals(ErrorCode.INVITE_ALREADY_ACCEPTED, ex.errorCode)
    }

    @Test
    fun `acceptInvite — 존재하지 않는 코드`() {
        val ex =
            assertThrows<BusinessException> {
                service.acceptInvite("invitee-1", "NOTFOUND")
            }
        assertEquals(ErrorCode.INVITE_NOT_FOUND, ex.errorCode)
    }

    @Test
    fun `acceptInvite 해피 패스는 양쪽 사용자에게 스페셜 코인 5개를 지급`() {
        val created = service.createInvite("inviter-1")
        val beforeInviter = userRepo.findById("inviter-1").get().specialCoin
        val beforeInvitee = userRepo.findById("invitee-1").get().specialCoin

        val response = service.acceptInvite("invitee-1", created.inviteCode)

        assertEquals(InviteService.ACCEPT_REWARD_SPECIAL_COINS, response.reward.specialCoins)
        assertEquals(
            beforeInviter + InviteService.ACCEPT_REWARD_SPECIAL_COINS,
            userRepo.findById("inviter-1").get().specialCoin,
        )
        assertEquals(
            beforeInvitee + InviteService.ACCEPT_REWARD_SPECIAL_COINS,
            userRepo.findById("invitee-1").get().specialCoin,
        )
        assertNotNull(inviteRepo.findByCode(created.inviteCode).get().acceptedAt)
    }

    @Test
    fun `existsAcceptedBetween — 수락 후 양 방향 모두 true`() {
        val created = service.createInvite("inviter-1")
        service.acceptInvite("invitee-1", created.inviteCode)

        assertTrue(inviteRepo.existsAcceptedBetween("inviter-1", "invitee-1"))
        assertTrue(inviteRepo.existsAcceptedBetween("invitee-1", "inviter-1"))
    }

    @Test
    fun `existsAcceptedBetween — 미수락 invite 는 false`() {
        service.createInvite("inviter-1") // 수락 전

        assertEquals(false, inviteRepo.existsAcceptedBetween("inviter-1", "invitee-1"))
    }

    // ─── Fakes ──────────────────────────────────────────────
    private class FakeInviteRepository :
        FakeJpaRepository<Invite, Long>(),
        InviteRepository {
        private var nextId = 1L

        override fun extractId(entity: Invite): Long = entity.id

        override fun assignId(entity: Invite): Invite {
            if (entity.id != 0L) return entity
            val idField = Invite::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
        }

        override fun findByCode(code: String): Optional<Invite> = Optional.ofNullable(store.values.firstOrNull { it.code == code })

        override fun existsByCode(code: String): Boolean = store.values.any { it.code == code }

        override fun existsAcceptedBetween(
            userIdA: String,
            userIdB: String,
        ): Boolean =
            store.values.any { i ->
                i.acceptedAt != null &&
                    (
                        (i.inviterUserId == userIdA && i.inviteeUserId == userIdB) ||
                            (i.inviterUserId == userIdB && i.inviteeUserId == userIdA)
                    )
            }
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }
}
