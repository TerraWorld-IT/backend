package com.terraworld.domain.terrarium

import com.terraworld.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "terrariums")
class Terrarium(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    val user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "background_id", nullable = false)
    var background: TerrariumBackground,
    // 낙서장 P2: 테라리움 티어 (화폐-게이트). 구 evolution_stage(레벨-게이트) 대체 신 SoT.
    @Column(name = "tier", nullable = false, length = 32)
    var tier: String = "GLASS_JAR",
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    // P-WILT-001 + J-WILT-001 (V11): 마지막 광고 시청 복구 시각.
    // computeWiltingState 가 max(last_record_date, this) 기준으로 days 계산.
    // NULL = 광고 복구 안 함 (기존 동작 동일).
    @Column(name = "wilt_recovered_at")
    var wiltRecoveredAt: LocalDateTime? = null,
    @OneToMany(mappedBy = "terrarium", cascade = [CascadeType.ALL], orphanRemoval = true)
    val placedItems: MutableList<TerrariumPlacement> = mutableListOf(),
)

@Entity
@Table(name = "terrarium_backgrounds")
class TerrariumBackground(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(nullable = false, length = 100)
    val name: String,
    @Column(name = "asset_url", nullable = false, length = 500)
    val assetUrl: String,
    @Column(name = "unlock_condition", nullable = false, length = 30)
    val unlockCondition: String = "DEFAULT",
    @Column(name = "unlock_value", nullable = false)
    val unlockValue: Int = 0,
)

@Entity
@Table(name = "terrarium_items")
class TerrariumPlacement(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "terrarium_id", nullable = false)
    val terrarium: Terrarium,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    val item: com.terraworld.domain.item.Item,
    @Column(name = "slot_id")
    val slotId: Int? = null,
    @Column(name = "pos_x", nullable = false)
    val posX: Float = 0f,
    @Column(name = "pos_y", nullable = false)
    val posY: Float = 0f,
    @Column(nullable = false)
    val rotation: Float = 0f,
    // 낙서장 자유배치 편집 영속(req3 #2): 크기/깊이/반전은 편집 시 갱신되므로 var.
    @Column(nullable = false)
    var scale: Float = 1f,
    @Column(name = "z_index", nullable = false)
    var zIndex: Int = 0,
    @Column(name = "flipped", nullable = false)
    var flipped: Boolean = false,
    @Column(name = "placed_at", nullable = false, updatable = false)
    val placedAt: LocalDateTime = LocalDateTime.now(),
    // N6 (구현 계획서 v4, 2026-05-21): J-FREE-001 자유배치 좌표.
    // V13 의 free_x_pixel / free_y_pixel / is_free_placement 컬럼 매핑.
    // free placement entitlement 보유 사용자가 슬롯 외 자유 위치에 배치 시 갱신 (var).
    @Column(name = "free_x_pixel")
    var freeXPixel: Int? = null,
    @Column(name = "free_y_pixel")
    var freeYPixel: Int? = null,
    @Column(name = "is_free_placement", nullable = false)
    var isFreePlacement: Boolean = false,
)
