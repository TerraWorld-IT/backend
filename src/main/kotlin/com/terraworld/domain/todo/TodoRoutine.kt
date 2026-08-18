package com.terraworld.domain.todo

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

/**
 * 반복 투두 루틴 정의 (apjek social loop, V38).
 *
 * 서버는 정의 CRUD 만 담당 — 오늘 할 일로의 구체화(인스턴스 생성)는 클라이언트 책임.
 * id 는 Long — TodoRoutineApi 확정안의 routineId 가 int64.
 * daysOfWeek 는 비트마스크 저장 (bit0=일 ... bit6=토, 0~127) — API 경계의 요일
 * Set(0=일..6=토, spec TodoRoutineRequest 규약)과의 변환은 [maskOf]/[daysOf] 가 담당. DAILY 는 0.
 * soft delete 는 isDeleted 플래그 (activity_records.is_deleted 관례).
 */
@Entity
@Table(name = "todo_routines")
class TodoRoutine(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String = "",
    @Column(nullable = false, length = 50)
    var label: String = "",
    @Column(name = "repeat_type", nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    var repeatType: TodoRepeatType = TodoRepeatType.DAILY,
    @Column(name = "days_of_week", nullable = false)
    var daysOfWeek: Int = 0,
    @Column(name = "is_deleted", nullable = false)
    var isDeleted: Boolean = false,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
) {
    companion object {
        /** 요일 Set(0=일..6=토, spec 규약) → 비트마스크 (bit0=일..bit6=토). 호출 전 0..6 범위 검증은 서비스 책임. */
        fun maskOf(days: Collection<Int>): Int = days.fold(0) { mask, day -> mask or (1 shl day) }

        /** 비트마스크 → 요일(0=일..6=토) 오름차순 리스트 (응답 매핑용). */
        fun daysOf(mask: Int): List<Int> = (0..6).filter { mask and (1 shl it) != 0 }
    }
}

enum class TodoRepeatType { DAILY, WEEKLY }

interface TodoRoutineRepository : JpaRepository<TodoRoutine, Long> {
    /** 활성(미삭제) 루틴 목록 — 생성순. */
    fun findAllByUserIdAndIsDeletedFalseOrderByCreatedAtAsc(userId: String): List<TodoRoutine>

    /** 활성 루틴 카운트 — 사용자당 20개 제한 판정. */
    fun countByUserIdAndIsDeletedFalse(userId: String): Long

    /** 소유자 스코프 단건 조회 (수정/삭제 대상 — 삭제된 루틴 제외). */
    fun findByIdAndUserIdAndIsDeletedFalse(
        id: Long,
        userId: String,
    ): TodoRoutine?

    /** 소유자 스코프 존재 확인 — 삭제 멱등 판정(이미 삭제 vs 미존재/타인)용. */
    fun existsByIdAndUserId(
        id: Long,
        userId: String,
    ): Boolean

    /**
     * per-userId 루틴 생성 직렬화용 tx-scoped advisory lock.
     * 활성 20개 제한이 count-then-insert 라 동시 요청 시 한도 초과(TOCTOU fail-open) —
     * 카운트 직전 획득해 race-free 로 만든다. tx 종료 시 자동 해제.
     * (RecordRepository.acquireRecordDailyLock 패턴 재사용.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireRoutineLock(
        @Param("key") key: String,
    ): Int
}
