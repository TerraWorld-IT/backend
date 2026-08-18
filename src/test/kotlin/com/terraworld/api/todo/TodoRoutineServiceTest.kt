package com.terraworld.api.todo

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.todo.TodoRepeatType
import com.terraworld.domain.todo.TodoRoutine
import com.terraworld.domain.todo.TodoRoutineRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TodoRoutineService 커버 (apjek social loop 투두 루틴 CRUD).
 * - create: label/daysOfWeek 검증, DAILY 정규화(0), 요일 Set(0=일..6=토)→비트마스크 변환, 활성 20개 제한
 * - list: 미삭제만 생성순
 * - update: 소유자의 활성 루틴만, 전체 필드 갱신
 * - delete: soft delete + 재삭제 멱등 + 미존재/타인 NOT_FOUND
 */
class TodoRoutineServiceTest {
    private lateinit var repo: FakeTodoRoutineRepository
    private lateinit var service: TodoRoutineService

    @BeforeEach
    fun setup() {
        repo = FakeTodoRoutineRepository()
        service = TodoRoutineService(repo)
    }

    @Test
    fun `create WEEKLY — 요일 Set(0=일_6=토)을 비트마스크로 저장 (월화수목금)`() {
        val saved = service.create("u", "아침 스트레칭", TodoRepeatType.WEEKLY, setOf(1, 2, 3, 4, 5))
        assertTrue(saved.id > 0)
        assertEquals(0b0111110, saved.daysOfWeek)
        assertEquals(listOf(1, 2, 3, 4, 5), TodoRoutine.daysOf(saved.daysOfWeek))
    }

    @Test
    fun `create DAILY — daysOfWeek 는 0 으로 정규화 (입력 무시)`() {
        val saved = service.create("u", "물 마시기", TodoRepeatType.DAILY, setOf(0, 1, 2, 3, 4, 5, 6))
        assertEquals(0, saved.daysOfWeek)
    }

    @Test
    fun `create — label 공백·50자 초과 INVALID_INPUT`() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "   ", TodoRepeatType.DAILY, null) }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "가".repeat(51), TodoRepeatType.DAILY, null) }.errorCode,
        )
    }

    @Test
    fun `create WEEKLY — 요일 없음·빈 Set·범위 밖(-1, 7) INVALID_INPUT`() {
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "요가", TodoRepeatType.WEEKLY, null) }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "요가", TodoRepeatType.WEEKLY, emptySet()) }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "요가", TodoRepeatType.WEEKLY, setOf(-1)) }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> { service.create("u", "요가", TodoRepeatType.WEEKLY, setOf(1, 7)) }.errorCode,
        )
    }

    @Test
    fun `create — 활성 20개 도달 시 TODO_ROUTINE_LIMIT_EXCEEDED`() {
        repeat(TodoRoutineService.MAX_ACTIVE_ROUTINES) { service.create("u", "루틴 $it", TodoRepeatType.DAILY, null) }
        assertEquals(
            ErrorCode.TODO_ROUTINE_LIMIT_EXCEEDED,
            assertThrows<BusinessException> { service.create("u", "21번째", TodoRepeatType.DAILY, null) }.errorCode,
        )
    }

    @Test
    fun `create — 삭제된 루틴은 20개 제한에 미포함`() {
        repeat(TodoRoutineService.MAX_ACTIVE_ROUTINES) { service.create("u", "루틴 $it", TodoRepeatType.DAILY, null) }
        service.delete("u", repo.all().first().id)
        val saved = service.create("u", "빈 자리 채우기", TodoRepeatType.DAILY, null)
        assertTrue(saved.id > 0)
    }

    @Test
    fun `list — 미삭제 루틴만, 타인 제외`() {
        val mine = service.create("u", "내 루틴", TodoRepeatType.DAILY, null)
        val deleted = service.create("u", "지운 루틴", TodoRepeatType.DAILY, null)
        service.delete("u", deleted.id)
        service.create("other", "남의 루틴", TodoRepeatType.DAILY, null)

        val listed = service.list("u")
        assertEquals(listOf(mine.id), listed.map { it.id })
    }

    @Test
    fun `update — 전체 필드 갱신 (WEEKLY 전환 포함, 주말 요일)`() {
        val saved = service.create("u", "독서", TodoRepeatType.DAILY, null)
        // 주말 = 일(0) + 토(6) — spec 의 0=일..6=토 규약.
        val updated = service.update("u", saved.id, "주말 독서", TodoRepeatType.WEEKLY, setOf(0, 6))
        assertEquals("주말 독서", updated.label)
        assertEquals(TodoRepeatType.WEEKLY, updated.repeatType)
        assertEquals(0b1000001, updated.daysOfWeek)
        assertEquals(listOf(0, 6), TodoRoutine.daysOf(updated.daysOfWeek))
    }

    @Test
    fun `update — 타인·미존재·삭제된 루틴 TODO_ROUTINE_NOT_FOUND`() {
        val other = service.create("other", "남의 루틴", TodoRepeatType.DAILY, null)
        assertEquals(
            ErrorCode.TODO_ROUTINE_NOT_FOUND,
            assertThrows<BusinessException> { service.update("u", other.id, "탈취", TodoRepeatType.DAILY, null) }.errorCode,
        )
        assertEquals(
            ErrorCode.TODO_ROUTINE_NOT_FOUND,
            assertThrows<BusinessException> { service.update("u", 999L, "없음", TodoRepeatType.DAILY, null) }.errorCode,
        )
        val deleted = service.create("u", "지운 루틴", TodoRepeatType.DAILY, null)
        service.delete("u", deleted.id)
        assertEquals(
            ErrorCode.TODO_ROUTINE_NOT_FOUND,
            assertThrows<BusinessException> { service.update("u", deleted.id, "부활", TodoRepeatType.DAILY, null) }.errorCode,
        )
    }

    @Test
    fun `delete — soft delete 후 재삭제는 멱등 no-op`() {
        val saved = service.create("u", "루틴", TodoRepeatType.DAILY, null)
        service.delete("u", saved.id)
        assertTrue(saved.isDeleted)
        service.delete("u", saved.id) // 멱등 — 예외 없음
    }

    @Test
    fun `delete — 미존재·타인 루틴 TODO_ROUTINE_NOT_FOUND`() {
        val other = service.create("other", "남의 루틴", TodoRepeatType.DAILY, null)
        assertEquals(
            ErrorCode.TODO_ROUTINE_NOT_FOUND,
            assertThrows<BusinessException> { service.delete("u", 999L) }.errorCode,
        )
        assertEquals(
            ErrorCode.TODO_ROUTINE_NOT_FOUND,
            assertThrows<BusinessException> { service.delete("u", other.id) }.errorCode,
        )
    }

    @Test
    fun `비트마스크 변환 — maskOf·daysOf 왕복 (월수금 + 일요일 경계)`() {
        val mask = TodoRoutine.maskOf(setOf(1, 3, 5))
        assertEquals(0b0101010, mask)
        assertEquals(listOf(1, 3, 5), TodoRoutine.daysOf(mask))
        // 경계: 일요일(0) = bit0, 토요일(6) = bit6.
        assertEquals(0b0000001, TodoRoutine.maskOf(setOf(0)))
        assertEquals(0b1000000, TodoRoutine.maskOf(setOf(6)))
        assertEquals(listOf(0, 6), TodoRoutine.daysOf(0b1000001))
    }

    private class FakeTodoRoutineRepository :
        FakeJpaRepository<TodoRoutine, Long>(),
        TodoRoutineRepository {
        private val seq = AtomicLong(0)

        override fun extractId(entity: TodoRoutine): Long = entity.id

        // IDENTITY 채번 재현 — id=0(미할당) 이면 reflection 으로 순번 할당.
        override fun assignId(entity: TodoRoutine): TodoRoutine {
            if (entity.id == 0L) {
                val field = TodoRoutine::class.java.getDeclaredField("id")
                field.isAccessible = true
                field.set(entity, seq.incrementAndGet())
            }
            return entity
        }

        override fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtAsc(userId: String): List<TodoRoutine> = store.values.filter { it.userId == userId && !it.isDeleted }

        override fun countByUserIdAndIsDeletedFalse(userId: String): Long = store.values.count { it.userId == userId && !it.isDeleted }.toLong()

        override fun findByIdAndUserIdAndIsDeletedFalse(
            id: Long,
            userId: String,
        ): TodoRoutine? = store.values.firstOrNull { it.id == id && it.userId == userId && !it.isDeleted }

        override fun existsByIdAndUserId(
            id: Long,
            userId: String,
        ): Boolean = store.values.any { it.id == id && it.userId == userId }

        override fun acquireRoutineLock(key: String): Int = 1
    }
}
