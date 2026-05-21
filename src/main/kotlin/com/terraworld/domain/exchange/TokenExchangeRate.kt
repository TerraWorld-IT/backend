package com.terraworld.domain.exchange

import com.terraworld.domain.category.Category
import jakarta.persistence.*

@Entity
@Table(name = "token_exchange_rates", uniqueConstraints = [UniqueConstraint(columnNames = ["from_category_id", "to_category_id"])])
class TokenExchangeRate(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_category_id", nullable = false)
    val fromCategory: Category,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_category_id", nullable = false)
    val toCategory: Category,
    // N5 (구현 계획서 v4): admin 교환 비율 조정 — 필드 var (AdminService.updateExchangeRate)
    @Column(nullable = false)
    var rate: Float = 2.0f,
    @Column(name = "is_active", nullable = false)
    var isActive: Boolean = true,
)
