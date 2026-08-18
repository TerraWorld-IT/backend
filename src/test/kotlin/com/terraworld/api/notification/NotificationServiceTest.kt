package com.terraworld.api.notification

import com.terraworld.domain.notification.NotificationType
import com.terraworld.domain.notification.UserNotification
import com.terraworld.domain.notification.UserNotificationRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * NotificationService 커버 (apjek social loop 인앱 알림함).
 * - append 저장 + 길이 초과 방어 절단
 * - list 최신순 페이지 / unreadCount
 * - markRead: 지정 ids 만 / 빈 ids 는 전체 읽음 / 타인·기읽음 미변경
 */
class NotificationServiceTest {
    private lateinit var repo: FakeUserNotificationRepository
    private lateinit var service: NotificationService

    @BeforeEach
    fun setup() {
        repo = FakeUserNotificationRepository()
        service = NotificationService(repo)
    }

    private fun notification(
        userId: String = "u",
        createdAt: LocalDateTime = LocalDateTime.now(),
        readAt: LocalDateTime? = null,
    ) = repo.save(
        UserNotification(
            userId = userId,
            type = NotificationType.SYSTEM,
            title = "제목",
            body = "본문",
            createdAt = createdAt,
            readAt = readAt,
        ),
    )

    @Test
    fun `append — 저장 후 id 할당 + 미읽음 상태`() {
        val saved = service.append("u", NotificationType.CHEER, "💌 응원", "화이팅!", route = "/record")
        assertNotNull(saved.id)
        assertEquals(NotificationType.CHEER, saved.type)
        assertEquals("/record", saved.route)
        assertNull(saved.readAt)
        assertEquals(1L, service.unreadCount("u"))
    }

    @Test
    fun `append — 컬럼 길이 초과 title·body 는 절단 저장 (저장 실패 방지)`() {
        val saved = service.append("u", NotificationType.SYSTEM, "가".repeat(150), "나".repeat(600))
        assertEquals(NotificationService.MAX_TITLE_LENGTH, saved.title.length)
        assertEquals(NotificationService.MAX_BODY_LENGTH, saved.body.length)
    }

    @Test
    fun `list — user 스코프 최신순 페이지`() {
        val base = LocalDateTime.of(2026, 8, 18, 12, 0)
        notification(createdAt = base.minusHours(2))
        val newest = notification(createdAt = base)
        notification(userId = "other", createdAt = base.plusHours(1))

        val page = service.list("u", PageRequest.of(0, 10))
        assertEquals(2, page.totalElements)
        assertEquals(newest.id, page.content.first().id)
    }

    @Test
    fun `unreadCount — 읽은 알림은 제외`() {
        notification()
        notification(readAt = LocalDateTime.now())
        assertEquals(1L, service.unreadCount("u"))
    }

    @Test
    fun `markRead 지정 ids — 해당 건만 읽음, 반환값은 전환 건수`() {
        val a = notification()
        val b = notification()
        val updated = service.markRead("u", listOf(a.id!!))
        assertEquals(1, updated)
        assertNotNull(a.readAt)
        assertNull(b.readAt)
    }

    @Test
    fun `markRead 빈 ids — 전체 읽음 처리`() {
        notification()
        notification()
        val updated = service.markRead("u", emptyList())
        assertEquals(2, updated)
        assertEquals(0L, service.unreadCount("u"))
    }

    @Test
    fun `markRead — 타인 알림 id 는 무시 (user 스코프)`() {
        val other = notification(userId = "other")
        val updated = service.markRead("u", listOf(other.id!!))
        assertEquals(0, updated)
        assertNull(other.readAt)
    }

    @Test
    fun `markRead — 이미 읽은 알림은 최초 읽음 시각 보존`() {
        val firstReadAt = LocalDateTime.of(2026, 8, 1, 9, 0)
        val read = notification(readAt = firstReadAt)
        val updated = service.markRead("u", listOf(read.id!!))
        assertEquals(0, updated)
        assertEquals(firstReadAt, read.readAt)
    }

    @Test
    fun `list — 페이지 크기 초과분은 다음 페이지`() {
        val base = LocalDateTime.of(2026, 8, 18, 12, 0)
        repeat(3) { notification(createdAt = base.plusMinutes(it.toLong())) }
        val first = service.list("u", PageRequest.of(0, 2))
        assertEquals(2, first.content.size)
        assertTrue(first.hasNext())
        val second = service.list("u", PageRequest.of(1, 2))
        assertEquals(1, second.content.size)
    }

    private class FakeUserNotificationRepository :
        FakeJpaRepository<UserNotification, UUID>(),
        UserNotificationRepository {
        override fun extractId(entity: UserNotification): UUID = entity.id ?: error("id 미할당 엔티티")

        // UUID @GeneratedValue 는 Hibernate 가 persist 시 할당 — fake 는 reflection 으로 재현.
        override fun assignId(entity: UserNotification): UserNotification {
            if (entity.id == null) {
                val field = UserNotification::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.set(entity, UUID.randomUUID())
            }
            return entity
        }

        override fun findAllByUserIdOrderByCreatedAtDesc(
            userId: String,
            pageable: Pageable,
        ): Page<UserNotification> {
            val sorted = store.values.filter { it.userId == userId }.sortedByDescending { it.createdAt }
            val slice = sorted.drop(pageable.pageNumber * pageable.pageSize).take(pageable.pageSize)
            return PageImpl(slice, pageable, sorted.size.toLong())
        }

        override fun countByUserIdAndReadAtIsNull(userId: String): Long = store.values.count { it.userId == userId && it.readAt == null }.toLong()

        override fun markRead(
            userId: String,
            ids: Collection<UUID>,
            now: LocalDateTime,
        ): Int {
            val targets = store.values.filter { it.userId == userId && it.id in ids && it.readAt == null }
            targets.forEach { it.readAt = now }
            return targets.size
        }

        override fun markAllRead(
            userId: String,
            now: LocalDateTime,
        ): Int {
            val targets = store.values.filter { it.userId == userId && it.readAt == null }
            targets.forEach { it.readAt = now }
            return targets.size
        }
    }
}
