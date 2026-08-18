package com.terraworld.domain.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 습관 응원 발송 원장 (apjek social loop, V38).
 *
 * 일 3회/트래커 rate-limit 판정의 SoT — user_notifications 는 발신자·트래커 컬럼이 없고
 * append 가 커밋 후 비동기(@Async AFTER_COMMIT)라 카운트 판정에 race 가 있어 별도 테이블로 기록한다.
 * cheeredDate 는 KST 기준 (KstTime.today()) — 일 단위 카운트 키.
 */
@Entity
@Table(name = "habit_cheers")
class HabitCheer(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "tracker_id", nullable = false)
    val trackerId: Long = 0,
    @Column(name = "from_user_id", nullable = false)
    val fromUserId: String = "",
    @Column(name = "to_user_id", nullable = false)
    val toUserId: String = "",
    @Column(nullable = false, length = 100)
    val message: String = "",
    @Column(name = "cheered_date", nullable = false)
    val cheeredDate: LocalDate = LocalDate.EPOCH,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

interface HabitCheerRepository : JpaRepository<HabitCheer, Long> {
    /** 일 3회/트래커 rate-limit 카운트 — (발신자, 트래커, KST 날짜) 인덱스 경로. */
    fun countByFromUserIdAndTrackerIdAndCheeredDate(
        fromUserId: String,
        trackerId: Long,
        cheeredDate: LocalDate,
    ): Long

    /**
     * per-(발신자|트래커) 응원 생성 직렬화용 tx-scoped advisory lock.
     * rate-limit 이 count-then-insert 라 동시 요청 시 한도 초과(TOCTOU fail-open) —
     * 카운트 직전 획득해 race-free 로 만든다. tx 종료 시 자동 해제.
     * (RecordRepository.acquireRecordDailyLock 패턴 재사용.)
     */
    @Query(
        value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(hashtextextended(:key, 0))) AS _lock",
        nativeQuery = true,
    )
    fun acquireCheerLock(
        @Param("key") key: String,
    ): Int
}
