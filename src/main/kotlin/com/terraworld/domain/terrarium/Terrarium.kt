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
    @OneToMany(mappedBy = "terrarium", cascade = [CascadeType.ALL], orphanRemoval = true)
    val placedItems: MutableList<TerrariumPlacement> = mutableListOf(),
)

/**
 * 5단계 테라리움 진화. 사용자 레벨 기반으로 자동 잠금 해제되며, 사용자는 잠금 해제된
 * 단계 사이에서 자유롭게 전환 가능. CUSTOM 은 freePlacement entitlement 추가 필요.
 */
enum class EvolutionStage(val unlockLevel: Int) {
    POT(1),
    BOTTLE(5),
    PALUDARIUM(10),
    WORLD(20),
    CUSTOM(30),
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
)
