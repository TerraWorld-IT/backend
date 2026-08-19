package com.terraworld.api.todo

import com.terraworld.domain.todo.TodoRepeatType
import com.terraworld.domain.todo.TodoRoutine
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.TodoRoutineApi
import io.terraworld.api.model.MessageResponse
import io.terraworld.api.model.TodoRoutineListResponse
import io.terraworld.api.model.TodoRoutineRequest
import io.terraworld.api.model.TodoRoutineResponse
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneOffset

/**
 * TodoRoutineApi 구현 — 반복 투두 루틴 CRUD (apjek social loop, V38).
 *
 * 서버는 루틴 정의만 관리 — 오늘 할 일 구체화(요일 매칭)는 클라이언트 책임.
 * daysOfWeek 는 spec 규약 0=일..6=토, 비트마스크 변환은 도메인([TodoRoutine.daysOf])이 담당.
 */
@Tag(name = "TodoRoutine", description = "투두 루틴 API")
@RestController
@RequestMapping("/api/v1")
class TodoRoutineController(
    private val todoRoutineService: TodoRoutineService,
) : TodoRoutineApi {
    @Operation(summary = "투두 루틴 목록", description = "본인의 활성(미삭제) 루틴 — 생성순")
    override fun listTodoRoutines(): ResponseEntity<TodoRoutineListResponse> =
        ResponseEntity.ok(
            TodoRoutineListResponse(
                routines = todoRoutineService.list(SecurityUtil.getCurrentUserId()).map { toApi(it) },
            ),
        )

    @Operation(summary = "투두 루틴 생성", description = "사용자당 활성 20개 제한. WEEKLY 는 daysOfWeek(0=일..6=토) 필수.")
    override fun createTodoRoutine(
        @Valid todoRoutineRequest: TodoRoutineRequest,
    ): ResponseEntity<TodoRoutineResponse> {
        val saved =
            todoRoutineService.create(
                userId = SecurityUtil.getCurrentUserId(),
                label = todoRoutineRequest.label,
                repeatType = TodoRepeatType.valueOf(todoRoutineRequest.repeatType.name),
                daysOfWeek = todoRoutineRequest.daysOfWeek,
            )
        return ResponseEntity.ok(toApi(saved))
    }

    @Operation(summary = "투두 루틴 수정", description = "본인 루틴의 label/repeatType/daysOfWeek 전체 수정 (PUT 계약)")
    override fun updateTodoRoutine(
        routineId: Long,
        @Valid todoRoutineRequest: TodoRoutineRequest,
    ): ResponseEntity<TodoRoutineResponse> {
        val updated =
            todoRoutineService.update(
                userId = SecurityUtil.getCurrentUserId(),
                routineId = routineId,
                label = todoRoutineRequest.label,
                repeatType = TodoRepeatType.valueOf(todoRoutineRequest.repeatType.name),
                daysOfWeek = todoRoutineRequest.daysOfWeek,
            )
        return ResponseEntity.ok(toApi(updated))
    }

    @Operation(summary = "투두 루틴 삭제", description = "soft delete — 이미 생성된 활동 기록은 영향 없음. 재삭제 멱등.")
    override fun deleteTodoRoutine(routineId: Long): ResponseEntity<MessageResponse> {
        todoRoutineService.delete(SecurityUtil.getCurrentUserId(), routineId)
        return ResponseEntity.ok(MessageResponse(message = "루틴을 삭제했습니다"))
    }

    private fun toApi(r: TodoRoutine): TodoRoutineResponse =
        TodoRoutineResponse(
            id = r.id,
            label = r.label,
            // domain TodoRepeatType 은 spec enum 과 1:1 (DAILY/WEEKLY) — forValue 직접 변환.
            repeatType = TodoRoutineResponse.RepeatType.forValue(r.repeatType.name),
            createdAt = r.createdAt.atOffset(ZoneOffset.UTC),
            // spec: DAILY 이면 null, WEEKLY 만 요일 목록(0=일..6=토).
            daysOfWeek = if (r.repeatType == TodoRepeatType.WEEKLY) TodoRoutine.daysOf(r.daysOfWeek) else null,
        )
}
