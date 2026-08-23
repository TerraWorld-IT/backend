package com.terraworld.api.ranking

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.social.Invite
import com.terraworld.domain.social.InviteRepository
import com.terraworld.domain.terrarium.TerrariumPlacementHistoryRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RankingService 단위 테스트.
 *
 * RecordRepository / UserRepository 는 메서드가 많아 in-memory fake 작성 비용이
 * 높다. 분기 검증이 목적이므로 mockito-kotlin 으로 mock 사용 (spring-boot-starter-test 포함).
 *
 * 커버리지 :
 *   - 잘못된 type 거부
 *   - 미래 yearMonth 거부
 *   - 잘못된 yearMonth 형식 거부
 *   - 동점자 올림픽 방식 rank (같은 rank, 다음 rank skip)
 *   - myRank — entries 안 / 밖 두 케이스
 *   - decoration 은 placeholder (빈 entries)
 *   - limit 클램핑 (10..100)
 */
class RankingServiceTest {
    private val ymFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM")
    private val currentYm: String = YearMonth.now().format(ymFmt)

    private lateinit var recordRepo: RecordRepository
    private lateinit var userRepo: UserRepository
    private lateinit var placementHistoryRepo: TerrariumPlacementHistoryRepository
    private lateinit var userItemRepo: UserItemRepository
    private lateinit var inviteRepo: InviteRepository
    private lateinit var service: RankingService

    @BeforeEach
    fun setup() {
        recordRepo = mock()
        userRepo = mock()
        placementHistoryRepo = mock()
        userItemRepo = mock()
        inviteRepo = mock()
        service = RankingService(recordRepo, userRepo, placementHistoryRepo, userItemRepo, inviteRepo)
    }

    @Test
    fun `잘못된 type 은 BAD_REQUEST`() {
        val ex =
            assertThrows<BusinessException> {
                service.getMonthly("user-1", "invalid", null, 50)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `미래 yearMonth 는 BAD_REQUEST`() {
        val future = YearMonth.now().plusMonths(1).format(ymFmt)
        val ex =
            assertThrows<BusinessException> {
                service.getMonthly("user-1", "engagement", future, 50)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `잘못된 yearMonth 형식은 BAD_REQUEST`() {
        val ex =
            assertThrows<BusinessException> {
                service.getMonthly("user-1", "engagement", "2026/04", 50)
            }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `engagement 동점자는 같은 rank 를 받고 다음 rank 는 skip 된다`() {
        // user-a/b 동점(10), user-c 5
        val rows =
            listOf(
                arrayOf<Any>("user-a", 10L),
                arrayOf<Any>("user-b", 10L),
                arrayOf<Any>("user-c", 5L),
            )
        whenever(recordRepo.findEngagementRanking(any(), any(), any<Pageable>()))
            .thenReturn(rows)
        whenever(userRepo.findAllById(listOf("user-a", "user-b", "user-c")))
            .thenReturn(
                listOf(
                    User(id = "user-a", nickname = "A"),
                    User(id = "user-b", nickname = "B"),
                    User(id = "user-c", nickname = "C"),
                ),
            )
        whenever(recordRepo.countByUserAndPeriod(eq("other"), any(), any())).thenReturn(0L)

        val response = service.getMonthly("other", "engagement", currentYm, 50)

        assertEquals(3, response.propertyEntries.size)
        assertEquals(1, response.propertyEntries[0].rank)
        assertEquals(1, response.propertyEntries[1].rank) // 동점
        assertEquals(3, response.propertyEntries[2].rank) // 2 skip
    }

    @Test
    fun `myRank — 본인이 entries 안에 있으면 그 rank 반환`() {
        val rows =
            listOf(
                arrayOf<Any>("user-a", 10L),
                arrayOf<Any>("self-1", 7L),
                arrayOf<Any>("user-c", 3L),
            )
        whenever(recordRepo.findEngagementRanking(any(), any(), any<Pageable>())).thenReturn(rows)
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(
                User(id = "user-a", nickname = "A"),
                User(id = "self-1", nickname = "Self"),
                User(id = "user-c", nickname = "C"),
            ),
        )
        whenever(recordRepo.countByUserAndPeriod(eq("self-1"), any(), any())).thenReturn(7L)

        val response = service.getMonthly("self-1", "engagement", currentYm, 50)

        assertEquals(2, response.myRank)
        assertEquals(7L, response.myScore)
        assertTrue(response.propertyEntries[1].isSelf == true)
    }

    @Test
    fun `myRank — 본인이 entries 밖이면 마지막 rank 다음 표시`() {
        val rows =
            listOf(
                arrayOf<Any>("user-a", 100L),
                arrayOf<Any>("user-b", 50L),
            )
        whenever(recordRepo.findEngagementRanking(any(), any(), any<Pageable>())).thenReturn(rows)
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(
                User(id = "user-a", nickname = "A"),
                User(id = "user-b", nickname = "B"),
            ),
        )
        whenever(recordRepo.countByUserAndPeriod(eq("outsider"), any(), any())).thenReturn(2L)

        val response = service.getMonthly("outsider", "engagement", currentYm, 50)

        assertEquals(3, response.myRank) // entries 마지막 rank(2) + 1
        assertEquals(2L, response.myScore)
    }

    // ─── 아프젝 v2: type=items / scope=friends ───

    @Test
    fun `items — 보유 아이템 수 랭킹, yearMonth null, 0점도 myRank 산출(동점 올림픽)`() {
        val rows =
            listOf(
                arrayOf<Any>("user-a", 5L),
                arrayOf<Any>("user-b", 2L),
                arrayOf<Any>("user-c", 2L),
            )
        whenever(userItemRepo.findItemsRanking(any<Pageable>())).thenReturn(rows)
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(User(id = "user-a", nickname = "A"), User(id = "user-b", nickname = "B"), User(id = "user-c", nickname = "C")),
        )
        whenever(userItemRepo.countDistinctSlugsByUserId("zero")).thenReturn(0L)

        val response = service.getMonthly("zero", "items", null, 50)

        assertEquals("items", response.type.value)
        assertEquals("all", response.scope.value)
        assertNull(response.yearMonth)
        assertEquals(2, response.propertyEntries[1].rank)
        assertEquals(2, response.propertyEntries[2].rank) // 동점
        // 0점이지만 myRank 산출 — entries 밖이라 entries 수 + 1
        assertEquals(4, response.myRank)
        assertEquals(0L, response.myScore)
    }

    @Test
    fun `items — 상위 N 밖인데 마지막 entry 와 동점이면 같은 rank (올림픽)`() {
        whenever(userItemRepo.findItemsRanking(any<Pageable>())).thenReturn(
            listOf(arrayOf<Any>("user-a", 5L), arrayOf<Any>("user-b", 2L)),
        )
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(User(id = "user-a", nickname = "A"), User(id = "user-b", nickname = "B")),
        )
        whenever(userItemRepo.countDistinctSlugsByUserId("tied")).thenReturn(2L)

        val response = service.getMonthly("tied", "items", "2026-04", 10)

        assertEquals(2, response.myRank)
        assertEquals("2026-04", response.yearMonth) // 요청값 echo
    }

    @Test
    fun `scope=friends — 본인 + 수락된 친구 집합으로 집계 (friends 질의 경로)`() {
        whenever(inviteRepo.findAcceptedInvolvingUser("me")).thenReturn(
            listOf(
                Invite(id = 1, code = "A", inviterUserId = "me", inviteeUserId = "f1", expiresAt = java.time.LocalDateTime.now()),
                Invite(id = 2, code = "B", inviterUserId = "f2", inviteeUserId = "me", expiresAt = java.time.LocalDateTime.now()),
            ),
        )
        whenever(userItemRepo.findItemsRankingAmong(eq(setOf("me", "f1", "f2")), any<Pageable>()))
            .thenReturn(listOf(arrayOf<Any>("f1", 3L), arrayOf<Any>("me", 1L)))
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(
            listOf(User(id = "f1", nickname = "F1"), User(id = "me", nickname = "Me")),
        )
        whenever(userItemRepo.countDistinctSlugsByUserId("me")).thenReturn(1L)

        val response = service.getMonthly("me", "items", null, 50, scope = "friends")

        assertEquals("friends", response.scope.value)
        assertEquals(2, response.propertyEntries.size)
        assertEquals(2, response.myRank)
        org.mockito.kotlin
            .verify(userItemRepo, org.mockito.kotlin.never())
            .findItemsRanking(any<Pageable>())
    }

    @Test
    fun `scope 가 all·friends 외면 BAD_REQUEST`() {
        val ex = assertThrows<BusinessException> { service.getMonthly("u", "items", null, 50, scope = "team") }
        assertEquals(ErrorCode.BAD_REQUEST, ex.errorCode)
    }

    @Test
    fun `scope=friends engagement — 친구 집합 질의를 사용한다`() {
        whenever(inviteRepo.findAcceptedInvolvingUser("me")).thenReturn(
            listOf(Invite(id = 1, code = "A", inviterUserId = "me", inviteeUserId = "f1", expiresAt = java.time.LocalDateTime.now())),
        )
        whenever(recordRepo.findEngagementRankingAmong(eq(setOf("me", "f1")), any(), any(), any<Pageable>()))
            .thenReturn(listOf(arrayOf<Any>("f1", 4L)))
        whenever(userRepo.findAllById(any<Iterable<String>>())).thenReturn(listOf(User(id = "f1", nickname = "F1")))
        whenever(recordRepo.countByUserAndPeriod(eq("me"), any(), any())).thenReturn(0L)

        val response = service.getMonthly("me", "engagement", currentYm, 50, scope = "friends")

        assertEquals(1, response.propertyEntries.size)
        assertNull(response.myRank)
    }

    @Test
    fun `myRank null — 본인 점수가 0 이면 null`() {
        whenever(recordRepo.findEngagementRanking(any(), any(), any<Pageable>())).thenReturn(emptyList())
        whenever(recordRepo.countByUserAndPeriod(eq("zero"), any(), any())).thenReturn(0L)

        val response = service.getMonthly("zero", "engagement", currentYm, 50)

        assertNull(response.myRank)
        assertNull(response.myScore)
    }

    @Test
    fun `decoration history 가 비어있으면 entries empty + myRank null`() {
        // mock() default — findDecorationRanking → [], countByUserAndPeriod → 0L
        val response = service.getMonthly("user-1", "decoration", currentYm, 50)

        assertEquals("decoration", response.type.value)
        assertEquals(currentYm, response.yearMonth)
        assertTrue(response.propertyEntries.isEmpty())
        assertNull(response.myRank)
        assertNull(response.myScore)
    }

    @Test
    fun `과거 yearMonth 는 정상 처리된다`() {
        val past = YearMonth.now().minusMonths(2).format(ymFmt)
        whenever(recordRepo.findEngagementRanking(any(), any(), any<Pageable>())).thenReturn(emptyList())
        whenever(recordRepo.countByUserAndPeriod(any(), any(), any())).thenReturn(0L)

        val response = service.getMonthly("user-1", "engagement", past, 50)

        assertEquals(past, response.yearMonth)
    }
}
