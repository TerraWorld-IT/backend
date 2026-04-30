package com.terraworld.domain.item

import com.terraworld.domain.category.Category
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "items")
class Item(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @Column(unique = true, length = 50)
    val slug: String? = null,
    @Column(nullable = false, length = 100)
    val name: String,
    @Column(columnDefinition = "TEXT")
    val description: String? = null,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    val category: Category? = null,
    @Column(name = "price_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    val priceType: PriceType,
    @Column(name = "price_amount", nullable = false)
    val priceAmount: Int,
    @Column(name = "token_price")
    val tokenPrice: Int? = null,
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val rarity: Rarity = Rarity.COMMON,
    @Column(name = "asset_url", nullable = false, length = 500)
    val assetUrl: String,
    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    val layout: ItemLayout = ItemLayout.FOREGROUND,
    @Column(name = "is_animated", nullable = false)
    val isAnimated: Boolean = false,
    @Column(nullable = false)
    val width: Int = 512,
    @Column(nullable = false)
    val height: Int = 512,
    @Column(name = "is_active", nullable = false)
    val isActive: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)

enum class PriceType { BASIC, SPECIAL, TOKEN, MIXED }

enum class Rarity { COMMON, RARE, EPIC }

enum class ItemLayout { FOREGROUND, BACKGROUND, FIGURE }
