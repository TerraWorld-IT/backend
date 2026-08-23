package com.terraworld.domain.record

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDateTime

/**
 * 친구 습관 페어 요청 (아프젝 v2, V39). 시작(START)/연장(EXTEND) 요청 1건 = 1행.
 * 수락 전 요청자·수신자 트래커는 PENDING(START) 또는 요청자만 PENDING(EXTEND) 이며,
 * 거절/취소/만료 시 트래커가 BROKEN 으로 전환된다. 7일 후 만료([expiresAt]) — 조회 시 lazy 만료 처리.
 * 응답의 partnerStatus/extendStatus 는 본 원장의 파생 뷰.
 */
@Entity
@Table(name = "habit_pair_requests")
class HabitPairRequest(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "requester_tracker_id", nullable = false)
    val requesterTrackerId: Long = 0,
    @Column(name = "requester_user_id", nullable = false)
    val requesterUserId: String = "",
    @Column(name = "partner_tracker_id")
    val partnerTrackerId: Long? = null,
    @Column(name = "partner_user_id", nullable = false)
    val partnerUserId: String = "",
    @Column(nullable = false, length = 8)
    @Enumerated(EnumType.STRING)
    val kind: HabitPairRequestKind = HabitPairRequestKind.START,
    @Column(nullable = false, length = 16)
    @Enumerated(EnumType.STRING)
    var status: HabitPairRequestStatus = HabitPairRequestStatus.REQUESTED,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "expires_at", nullable = false)
    val expiresAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "responded_at")
    var respondedAt: LocalDateTime? = null,
) {
    /** 요청자·수신자 양측 트래커 id 중 [trackerId] 의 상대 id. 관여하지 않는 트래커면 null. */
    fun counterpartOf(trackerId: Long): Long? =
        when (trackerId) {
            requesterTrackerId -> partnerTrackerId
            partnerTrackerId -> requesterTrackerId
            else -> null
        }

    fun isOpen(): Boolean = status == HabitPairRequestStatus.REQUESTED

    fun isExpired(now: LocalDateTime): Boolean = isOpen() && now.isAfter(expiresAt)
}

enum class HabitPairRequestKind { START, EXTEND }

enum class HabitPairRequestStatus { REQUESTED, ACCEPTED, DECLINED, CANCELLED, EXPIRED }

interface HabitPairRequestRepository : JpaRepository<HabitPairRequest, Long> {
    /** 트래커 집합이 요청자 또는 수신자로 관여한 요청 전부 — 목록 응답의 partnerStatus/extendStatus 배치 해석. */
    fun findAllByRequesterTrackerIdInOrPartnerTrackerIdIn(
        requesterTrackerIds: Collection<Long>,
        partnerTrackerIds: Collection<Long>,
    ): List<HabitPairRequest>
}
