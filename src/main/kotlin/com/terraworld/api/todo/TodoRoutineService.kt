package com.terraworld.api.todo

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.todo.TodoRepeatType
import com.terraworld.domain.todo.TodoRoutine
import com.terraworld.domain.todo.TodoRoutineRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * 투두 루틴 서비스 (apjek social loop) — 반복 투두 정의 CRUD.
 *
 * 서버는 정의만 관리한다. 오늘 할 일로의 구체화(요일 매칭·인스턴스 생성)는 클라이언트 책임.
 * daysOfWeek 는 API 계약(TodoRoutineRequest)과 동일한 요일 Set(0=일..6=토)으로 받고
 * 저장은 비트마스크 ([TodoRoutine.maskOf]) — DAILY 는 무시(0), WEEKLY 는 최소 1개 요일 필수.
 */
@Service
class TodoRoutineService(
    private val todoRoutineRepository: TodoRoutineRepository,
) {
    companion object {
        /** 사용자당 활성(미삭제) 루틴 상한. */
        const val MAX_ACTIVE_ROUTINES = 20
        const val MAX_LABEL_LENGTH = 50
    }

    @Transactional(readOnly = true)
    fun list(userId: String): List<TodoRoutine> = todoRoutineRepository.findAllByUserIdAndIsDeletedFalseOrderByCreatedAtAsc(userId)

    @Transactional
    fun create(
        userId: String,
        label: String,
        repeatType: TodoRepeatType,
        daysOfWeek: Set<Int>?,
    ): TodoRoutine {
        val normalizedLabel = validateLabel(label)
        val mask = validateDaysOfWeek(repeatType, daysOfWeek)

        // 활성 20개 제한 — count-then-insert 의 TOCTOU 를 per-user advisory lock 으로 직렬화
        // (RecordRepository.acquireRecordDailyLock 패턴).
        todoRoutineRepository.acquireRoutineLock("todo-routine|$userId")
        if (todoRoutineRepository.countByUserIdAndIsDeletedFalse(userId) >= MAX_ACTIVE_ROUTINES) {
            throw BusinessException(ErrorCode.TODO_ROUTINE_LIMIT_EXCEEDED)
        }

        return todoRoutineRepository.save(
            TodoRoutine(
                userId = userId,
                label = normalizedLabel,
                repeatType = repeatType,
                daysOfWeek = mask,
            ),
        )
    }

    /** 전체 필드 갱신 (label/repeatType/daysOfWeek — PUT 계약) — 소유자의 활성 루틴만. */
    @Transactional
    fun update(
        userId: String,
        routineId: Long,
        label: String,
        repeatType: TodoRepeatType,
        daysOfWeek: Set<Int>?,
    ): TodoRoutine {
        val routine =
            todoRoutineRepository.findByIdAndUserIdAndIsDeletedFalse(routineId, userId)
                ?: throw BusinessException(ErrorCode.TODO_ROUTINE_NOT_FOUND)
        routine.label = validateLabel(label)
        routine.repeatType = repeatType
        routine.daysOfWeek = validateDaysOfWeek(repeatType, daysOfWeek)
        return todoRoutineRepository.save(routine)
    }

    /**
     * soft delete (is_deleted=true — activity_records 관례). 이미 삭제된 자기 루틴의 재삭제는
     * 멱등 no-op, 미존재/타인 루틴은 TODO_ROUTINE_NOT_FOUND (HabitService.stop 판정 패턴).
     */
    @Transactional
    fun delete(
        userId: String,
        routineId: Long,
    ) {
        val routine = todoRoutineRepository.findByIdAndUserIdAndIsDeletedFalse(routineId, userId)
        if (routine == null) {
            if (!todoRoutineRepository.existsByIdAndUserId(routineId, userId)) {
                throw BusinessException(ErrorCode.TODO_ROUTINE_NOT_FOUND)
            }
            return // 이미 삭제됨 — 멱등
        }
        routine.isDeleted = true
        todoRoutineRepository.save(routine)
    }

    private fun validateLabel(label: String): String {
        val trimmed = label.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_LABEL_LENGTH) {
            throw BusinessException(ErrorCode.INVALID_INPUT, "루틴 이름은 1~${MAX_LABEL_LENGTH}자여야 합니다")
        }
        return trimmed
    }

    /**
     * 요일 Set(0=일..6=토, spec TodoRoutineRequest 규약) → 저장용 비트마스크.
     * DAILY 는 요일 지정이 무의미 — 0 으로 정규화. WEEKLY 는 0..6 범위의 요일 최소 1개 필수.
     */
    private fun validateDaysOfWeek(
        repeatType: TodoRepeatType,
        daysOfWeek: Set<Int>?,
    ): Int =
        when (repeatType) {
            TodoRepeatType.DAILY -> 0
            TodoRepeatType.WEEKLY -> {
                if (daysOfWeek.isNullOrEmpty() || daysOfWeek.any { it !in 0..6 }) {
                    throw BusinessException(ErrorCode.INVALID_INPUT, "반복 요일(0=일~6=토)을 1개 이상 선택해 주세요")
                }
                TodoRoutine.maskOf(daysOfWeek)
            }
        }
}
