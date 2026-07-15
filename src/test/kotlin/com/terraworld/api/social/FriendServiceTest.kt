package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.social.TargetLikeCountProjection
import com.terraworld.domain.social.TerrariumLike
import com.terraworld.domain.social.TerrariumLikeRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FriendService 의 핵심 분기 커버. Mockito 대신 [FakeJpaRepository] 기반 in-memory fake 를
 * 사용해 Spring context 없이 돈다 (InviteServiceTest 와 동일 패턴). TerrariumService 는
 * 놀러가기 경로 외에는 호출되지 않으므로 mock.
 *
 * 커버리지:
 * - listFriends: 친구 목록 + like 집계 (수락 invite 양 방향) / 친구 없으면 empty
 * - toggleLike: 좋아요 추가 (count 증가) / 토글 취소 / 본인 좋아요 거부 / 비친구 거부
 */
class FriendServiceTest {
    private lateinit var inviteRepo: FakeInviteRepository
    private lateinit var userRepo: FakeUserRepository
    private lateinit var likeRepo: FakeTerrariumLikeRepository
    private lateinit var service: FriendService

    @BeforeEach
    fun setup() {
        inviteRepo = FakeInviteRepository()
        userRepo = FakeUserRepository()
        likeRepo = FakeTerrariumLikeRepository()
        // 놀러가기(visitFriendTerrarium) 경로는 본 test 에서 검증하지 않으므로 mock.
        val terrariumService =
            org.mockito.Mockito.mock(com.terraworld.api.terrarium.TerrariumService::class.java)
        service =
            FriendService(
                inviteRepo,
                userRepo,
                likeRepo,
                terrariumService,
            )

        userRepo.save(User(id = "me", nickname = "나"))
        userRepo.save(User(id = "friend-a", nickname = "친구A"))
        userRepo.save(User(id = "friend-b", nickname = "친구B"))
        userRepo.save(User(id = "stranger", nickname = "남남"))
    }

    /** "me" 가 inviter 또는 invitee 인 수락된 invite 를 추가하는 헬퍼. */
    private fun acceptFriendship(
        inviter: String,
        invitee: String,
    ) {
        inviteRepo.save(
            Invite(
                code = "C-$inviter-$invitee",
                inviterUserId = inviter,
                inviteeUserId = invitee,
                expiresAt = LocalDateTime.now().plusDays(1),
                acceptedAt = LocalDateTime.now(),
            ),
        )
    }

    // ─── listFriends ───────────────────────────────────────────

    @Test
    fun `listFriends — 수락된 invite 의 상대방을 like 집계와 함께 반환 (양 방향)`() {
        // me 가 inviter 인 케이스 + me 가 invitee 인 케이스 둘 다 — 상대방이 friendIds 에 들어가야 함
        acceptFriendship(inviter = "me", invitee = "friend-a")
        acceptFriendship(inviter = "friend-b", invitee = "me")
        // friend-a 에게 좋아요 2개, friend-b 에게 0개
        likeRepo.save(TerrariumLike(likerUserId = "x", targetUserId = "friend-a"))
        likeRepo.save(TerrariumLike(likerUserId = "y", targetUserId = "friend-a"))

        val friends = service.listFriends("me")

        assertEquals(2, friends.size)
        val byId = friends.associateBy { it.userId }
        assertEquals("친구A", byId["friend-a"]!!.nickname)
        assertEquals(2L, byId["friend-a"]!!.likeCount)
        assertEquals("친구B", byId["friend-b"]!!.nickname)
        assertEquals(0L, byId["friend-b"]!!.likeCount)
    }

    @Test
    fun `listFriends — 수락된 invite 가 없으면 빈 목록`() {
        // 미수락 invite 만 존재 — friendIds 가 비어 empty 반환
        inviteRepo.save(
            Invite(
                code = "PENDING",
                inviterUserId = "me",
                inviteeUserId = "friend-a",
                expiresAt = LocalDateTime.now().plusDays(1),
                acceptedAt = null,
            ),
        )

        assertTrue(service.listFriends("me").isEmpty())
    }

    // ─── toggleLike ────────────────────────────────────────────

    @Test
    fun `toggleLike — 친구에게 처음 좋아요하면 liked=true 이고 count 증가`() {
        acceptFriendship(inviter = "me", invitee = "friend-a")

        val result = service.toggleLike("me", "friend-a")

        assertTrue(result.liked)
        assertEquals(1L, result.likeCount)
        assertTrue(likeRepo.findByLikerUserIdAndTargetUserId("me", "friend-a").isPresent)
    }

    @Test
    fun `toggleLike — 이미 좋아요한 상태면 취소되어 liked=false 이고 count 감소`() {
        acceptFriendship(inviter = "me", invitee = "friend-a")
        likeRepo.save(TerrariumLike(likerUserId = "me", targetUserId = "friend-a"))

        val result = service.toggleLike("me", "friend-a")

        assertFalse(result.liked)
        assertEquals(0L, result.likeCount)
        assertFalse(likeRepo.findByLikerUserIdAndTargetUserId("me", "friend-a").isPresent)
    }

    @Test
    fun `toggleLike — 본인 테라리움 좋아요는 INVALID_INPUT`() {
        val ex =
            assertThrows<BusinessException> {
                service.toggleLike("me", "me")
            }
        assertEquals(ErrorCode.INVALID_INPUT, ex.errorCode)
    }

    @Test
    fun `toggleLike — 친구가 아닌 사용자 좋아요는 FORBIDDEN`() {
        // me 와 stranger 사이엔 수락된 invite 가 없음
        val ex =
            assertThrows<BusinessException> {
                service.toggleLike("me", "stranger")
            }
        assertEquals(ErrorCode.FORBIDDEN, ex.errorCode)
        // 거부 시 like row 가 생기면 안 됨
        assertFalse(likeRepo.findByLikerUserIdAndTargetUserId("me", "stranger").isPresent)
    }

    // ─── Fakes ─────────────────────────────────────────────────

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

        override fun findAcceptedInvolvingUser(userId: String): List<Invite> =
            store.values.filter { i ->
                i.acceptedAt != null && (i.inviterUserId == userId || i.inviteeUserId == userId)
            }
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeTerrariumLikeRepository :
        FakeJpaRepository<TerrariumLike, Long>(),
        TerrariumLikeRepository {
        private var nextId = 1L

        override fun extractId(entity: TerrariumLike): Long = entity.id

        override fun assignId(entity: TerrariumLike): TerrariumLike {
            if (entity.id != 0L) return entity
            val idField = TerrariumLike::class.java.getDeclaredField("id")
            idField.isAccessible = true
            idField.setLong(entity, nextId++)
            return entity
        }

        override fun findByLikerUserIdAndTargetUserId(
            likerUserId: String,
            targetUserId: String,
        ): Optional<TerrariumLike> =
            Optional.ofNullable(
                store.values.firstOrNull {
                    it.likerUserId == likerUserId && it.targetUserId == targetUserId
                },
            )

        override fun countByTargetUserId(targetUserId: String): Long = store.values.count { it.targetUserId == targetUserId }.toLong()

        // BE-10: listFriends 의 IN + GROUP BY 집계 (like 0건 target 은 결과에 없음 — 실 쿼리 동일)
        override fun countGroupedByTargetUserIdIn(targetUserIds: Collection<String>): List<TargetLikeCountProjection> =
            store.values
                .filter { it.targetUserId in targetUserIds }
                .groupBy { it.targetUserId }
                .map { (target, likes) ->
                    object : TargetLikeCountProjection {
                        override val targetUserId: String = target
                        override val likeCount: Long = likes.size.toLong()
                    }
                }
    }
}
