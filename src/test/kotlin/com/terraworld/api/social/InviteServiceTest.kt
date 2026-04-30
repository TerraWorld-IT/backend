package com.terraworld.api.social

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.domain.Example
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.domain.Sort
import org.springframework.data.repository.query.FluentQuery
import java.time.LocalDateTime
import java.util.Optional
import java.util.function.Function
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * InviteService 의 핵심 분기를 검증한다. Mockito 대신 in-memory fake 를 사용해
 * Spring context 없이 빠르게 돈다.
 *
 * 커버리지: 초대 발급 / 자기 초대 거부 / 만료 / 중복 수락 / 해피 패스.
 * 더 깊은 동시성 / 코드 충돌 시나리오는 추후 @DataJpaTest 로 격상할 수 있다.
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

        assertEquals(8, response.inviteCode.length)
        assertTrue(response.inviteLink.toString().endsWith(response.inviteCode))
        assertNotNull(response.expiresAt)
        assertEquals(1, inviteRepo.all().size)
    }

    @Test
    fun `acceptInvite 는 본인 초대 수락을 거부한다`() {
        val created = service.createInvite("inviter-1")

        val ex =
            assertThrows<BusinessException> {
                service.acceptInvite("inviter-1", created.inviteCode)
            }
        assertEquals(ErrorCode.INVITE_SELF_ACCEPT, ex.errorCode)
    }

    @Test
    fun `acceptInvite 는 만료된 초대를 거부한다`() {
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
    fun `acceptInvite 는 이미 수락된 초대를 거부한다`() {
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
    fun `acceptInvite 해피 패스는 양쪽 사용자에게 스페셜 코인 5개를 지급한다`() {
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
    }

    // ─── Fakes ──────────────────────────────────────────────
    private class FakeInviteRepository : InviteRepository {
        private val store = mutableMapOf<Long, Invite>()
        private var nextId = 1L

        fun all(): List<Invite> = store.values.toList()

        override fun findByCode(code: String): Optional<Invite> = Optional.ofNullable(store.values.firstOrNull { it.code == code })

        override fun existsByCode(code: String): Boolean = store.values.any { it.code == code }

        override fun <S : Invite> save(entity: S): S {
            if (entity.id == 0L) {
                val idField = Invite::class.java.getDeclaredField("id")
                idField.isAccessible = true
                idField.setLong(entity, nextId++)
            }
            store[entity.id] = entity
            return entity
        }

        // ─── 이하 JpaRepository 인터페이스 더미 구현 (미사용) ───
        override fun findAll(): List<Invite> = store.values.toList()

        override fun findAll(sort: Sort): List<Invite> = store.values.toList()

        override fun findAll(pageable: Pageable): Page<Invite> = Page.empty()

        override fun findAllById(ids: Iterable<Long>): List<Invite> = emptyList()

        override fun count(): Long = store.size.toLong()

        override fun deleteById(id: Long) {
            store.remove(id)
        }

        override fun delete(entity: Invite) {
            store.remove(entity.id)
        }

        override fun deleteAllById(ids: Iterable<Long>) = ids.forEach { store.remove(it) }

        override fun deleteAll(entities: Iterable<Invite>) = entities.forEach { store.remove(it.id) }

        override fun deleteAll() {
            store.clear()
        }

        override fun findById(id: Long): Optional<Invite> = Optional.ofNullable(store[id])

        override fun existsById(id: Long): Boolean = store.containsKey(id)

        override fun <S : Invite> saveAll(entities: Iterable<S>): List<S> = entities.map { save(it) }

        override fun flush() {}

        override fun <S : Invite> saveAndFlush(entity: S): S = save(entity)

        override fun <S : Invite> saveAllAndFlush(entities: Iterable<S>): List<S> = entities.map(::save)

        override fun deleteAllInBatch(entities: Iterable<Invite>) = deleteAll(entities)

        override fun deleteAllByIdInBatch(ids: Iterable<Long>) = deleteAllById(ids)

        override fun deleteAllInBatch() = deleteAll()

        override fun getOne(id: Long): Invite = store[id] ?: error("not found")

        override fun getById(id: Long): Invite = store[id] ?: error("not found")

        override fun getReferenceById(id: Long): Invite = store[id] ?: error("not found")

        override fun <S : Invite> findOne(example: Example<S>): Optional<S> = Optional.empty()

        override fun <S : Invite> findAll(example: Example<S>): List<S> = emptyList()

        override fun <S : Invite> findAll(
            example: Example<S>,
            sort: Sort,
        ): List<S> = emptyList()

        override fun <S : Invite> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = Page.empty()

        override fun <S : Invite> count(example: Example<S>): Long = 0

        override fun <S : Invite> exists(example: Example<S>): Boolean = false

        override fun <S : Invite, R : Any> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = error("unsupported")
    }

    private class FakeUserRepository : UserRepository {
        private val store = mutableMapOf<String, User>()

        override fun <S : User> save(entity: S): S {
            store[entity.id] = entity
            return entity
        }

        override fun findById(id: String): Optional<User> = Optional.ofNullable(store[id])

        override fun existsById(id: String): Boolean = store.containsKey(id)

        override fun findAll(): List<User> = store.values.toList()

        override fun findAll(sort: Sort): List<User> = store.values.toList()

        override fun findAll(pageable: Pageable): Page<User> = Page.empty()

        override fun findAllById(ids: Iterable<String>): List<User> = emptyList()

        override fun count(): Long = store.size.toLong()

        override fun deleteById(id: String) {
            store.remove(id)
        }

        override fun delete(entity: User) {
            store.remove(entity.id)
        }

        override fun deleteAllById(ids: Iterable<String>) = ids.forEach { store.remove(it) }

        override fun deleteAll(entities: Iterable<User>) = entities.forEach { store.remove(it.id) }

        override fun deleteAll() {
            store.clear()
        }

        override fun <S : User> saveAll(entities: Iterable<S>): List<S> = entities.map { save(it) }

        override fun flush() {}

        override fun <S : User> saveAndFlush(entity: S): S = save(entity)

        override fun <S : User> saveAllAndFlush(entities: Iterable<S>): List<S> = entities.map(::save)

        override fun deleteAllInBatch(entities: Iterable<User>) = deleteAll(entities)

        override fun deleteAllByIdInBatch(ids: Iterable<String>) = deleteAllById(ids)

        override fun deleteAllInBatch() = deleteAll()

        override fun getOne(id: String): User = store[id] ?: error("not found")

        override fun getById(id: String): User = store[id] ?: error("not found")

        override fun getReferenceById(id: String): User = store[id] ?: error("not found")

        override fun <S : User> findOne(example: Example<S>): Optional<S> = Optional.empty()

        override fun <S : User> findAll(example: Example<S>): List<S> = emptyList()

        override fun <S : User> findAll(
            example: Example<S>,
            sort: Sort,
        ): List<S> = emptyList()

        override fun <S : User> findAll(
            example: Example<S>,
            pageable: Pageable,
        ): Page<S> = Page.empty()

        override fun <S : User> count(example: Example<S>): Long = 0

        override fun <S : User> exists(example: Example<S>): Boolean = false

        override fun <S : User, R : Any> findBy(
            example: Example<S>,
            queryFunction: Function<FluentQuery.FetchableFluentQuery<S>, R>,
        ): R = error("unsupported")
    }
}
