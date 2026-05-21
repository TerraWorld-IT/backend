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
    @Column(name = "evolution_stage", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    var evolutionStage: EvolutionStage = EvolutionStage.BOTTLE,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: LocalDateTime = LocalDateTime.now(),
    // P-WILT-001 + J-WILT-001 (V11): 마지막 광고 시청 복구 시각.
    // computeWiltingState 가 max(last_record_date, this) 기준으로 days 계산.
    // NULL = 광고 복구 안 함 (기존 동작 동일).
    @Column(name = "wilt_recovered_at")
    var wiltRecoveredAt: LocalDateTime? = null,
    // P-EVOLVE-001 + J-EVO-001 (V12): level-up 으로 신규 EvolutionStage unlock 시 자동 갱신.
    // frontend 의 모달 G (UpgradeModal) 가 본 timestamp 비교로 표시 결정.
    @Column(name = "evolution_unlocked_at")
    var evolutionUnlockedAt: LocalDateTime? = null,
    @OneToMany(mappedBy = "terrarium", cascade = [CascadeType.ALL], orphanRemoval = true)
    val placedItems: MutableList<TerrariumPlacement> = mutableListOf(),
)

/**
 * 5단계 테라리움 진화. 사용자 레벨 기반으로 자동 잠금 해제되며, 사용자는 잠금 해제된
 * 단계 사이에서 자유롭게 전환 가능. CUSTOM 은 freePlacement entitlement 추가 필요.
 *
 * N4 (구현 계획서 v4, 2026-05-21): unlockLevel 을 level_configs seed 범위 (level 1~10) 안으로
 * 조정. 이전 WORLD(20)/CUSTOM(30) 은 level_configs 가 level 10 까지만 seed 돼 영구 도달 불가였음.
 * 옵션 A (권장 default) 적용 — enum 선언 순서 (POT→BOTTLE→PALUDARIUM→WORLD→CUSTOM) 유지,
 * unlockLevel 만 1~10 범위로. MVP default 는 BOTTLE (level 1 사용자도 BOTTLE 표시 — unlockLevel
 * 은 "그 단계로 진화 가능한 최소 level" 이지 강제 강등이 아님).
 */
enum class EvolutionStage(
    val unlockLevel: Int,
) {
    POT(1),
    BOTTLE(2),
    PALUDARIUM(5),
    WORLD(8),
    CUSTOM(10),
    ;

    companion object {
        fun unlockedFor(userLevel: Int): List<EvolutionStage> = entries.filter { userLevel >= it.unlockLevel }
    }
}

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
    @Column(nullable = false)
    val scale: Float = 1f,
    @Column(name = "z_index", nullable = false)
    val zIndex: Int = 0,
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
