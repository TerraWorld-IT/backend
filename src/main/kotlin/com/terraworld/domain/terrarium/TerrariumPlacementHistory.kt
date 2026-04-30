package com.terraworld.domain.terrarium

import jakarta.persistence.*
import java.time.LocalDateTime

/**
 * 테라리움 신규 배치 이력. 월간 decoration 랭킹의 source.
 *
 * 매번 새 배치(또는 교체) 가 발생할 때마다 row 추가. 같은 itemId 의 위치 미세 조정은
 * history 추가 안 함 (인플레이션 방지). RankingService 가 user_id / placed_at 로
 * 그룹핑해 월간 카운트를 산정.
 *
 * 도입 마이그레이션: V7__add_photo_url_to_records.sql
 */
@Entity
@Table(name = "terrarium_placement_history")
class TerrariumPlacementHistory(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "item_id", nullable = false)
    val itemId: Long,
    @Column(name = "slot_id", nullable = false)
    val slotId: Int,
    @Column(name = "placed_at", nullable = false, updatable = false)
    val placedAt: LocalDateTime = LocalDateTime.now(),
)
