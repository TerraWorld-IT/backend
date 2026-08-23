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
    private lateinit var currencyService: com.terraworld.api.currency.CurrencyService
    private lateinit var service: InviteService

    @BeforeEach
    fun setup() {
        inviteRepo = FakeInviteRepository()
        userRepo = FakeUserRepository()
        // N2/N8/N16 (구현 계획서 v4): InviteService 생성자에 walletTransactionService +
        // eventPublisher + terrariumService 추가. 본 test 는 ledger / FCM / share 경로를
        // 검증하지 않으므로 mock.
        val walletTransactionService =
            org.mockito.Mockito.mock(com.terraworld.api.wallet.WalletTransactionService::class.java)
        val eventPublisher =
            org.mockito.Mockito.mock(org.springframework.context.ApplicationEventPublisher::class.java)
        val terrariumService =
            org.mockito.Mockito.mock(com.terraworld.api.terrarium.TerrariumService::class.java)
        currencyService = org.mockito.Mockito.mock(com.terraworld.api.currency.CurrencyService::class.java)
        service =
            InviteService(
                inviteRepo,
                userRepo,
                currencyService,
                walletTransactionService,
                eventPublisher,
                terrariumService,
                "https://terraworld.app/share",
            )

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
    fun `acceptInvite 해피 패스는 수락자 RUBY 10 초대자 RUBY 30 credit (아프젝 v2 기본 설정)`() {
        val created = service.createInvite("inviter-1")
        // 발급 응답에 공유 카피용 보상값 동봉
        assertEquals(30, created.inviterRuby)
        assertEquals(10, created.inviteeRuby)

        val response = service.acceptInvite("invitee-1", created.inviteCode)

        assertEquals(10, response.reward.specialCoins)
        assertEquals(10, response.reward.inviteeRuby)
        assertEquals(30, response.reward.inviterRuby)
        // 낙서장 P1: 보상 = 양측 RUBY credit (신 substrate)
        org.mockito.kotlin.verify(currencyService).credit(
            org.mockito.kotlin.eq("invitee-1"),
            org.mockito.kotlin.eq("RUBY"),
            org.mockito.kotlin.eq(10L),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
            org.mockito.kotlin.anyOrNull(),
        )
        org.mockito.kotlin.verify(currencyService).credit(
            org.mockito.kotlin.eq("inviter-1"),
            org.mockito.kotlin.eq("RUBY"),
            org.mockito.kotlin.eq(30L),
            org.mockito.kotlin.any(),
            org.mockito.kotlin.anyOrNull(),
            org.mockito.kotlin.anyOrNull(),
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

        override fun acquireInvitePairLock(key: String): Int = 1

        override fun claimAccept(
            id: Long,
            inviteeUserId: String,
            now: LocalDateTime,
        ): Int {
            val invite = store[id] ?: return 0
            if (invite.acceptedAt != null) return 0
            invite.inviteeUserId = inviteeUserId
            invite.acceptedAt = now
            return 1
        }

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

        override fun findAcceptedBetween(
            userIdA: String,
            userIdB: String,
        ): Optional<Invite> =
            Optional.ofNullable(
                store.values.firstOrNull { i ->
                    i.acceptedAt != null &&
                        (
                            (i.inviterUserId == userIdA && i.inviteeUserId == userIdB) ||
                                (i.inviterUserId == userIdB && i.inviteeUserId == userIdA)
                        )
                },
            )

        // P-FRIEND-001 (구현 계획서 v4): 친구 목록 쿼리 fake 구현.
        override fun findAcceptedInvolvingUser(userId: String): List<Invite> =
            store.values.filter { i ->
                i.acceptedAt != null && (i.inviterUserId == userId || i.inviteeUserId == userId)
            }
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id

        override fun acquireBootstrapLock(key: String): Int = 1
    }
}
