package com.terraworld.domain.wallet

import com.terraworld.domain.category.Category
import com.terraworld.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "wallet_transactions")
class WalletTransaction(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @Column(name = "currency_type", nullable = false, length = 30)
    val currencyType: String,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    val category: Category? = null,
    @Column(nullable = false)
    val amount: Long,
    @Column(name = "balance_after", nullable = false)
    val balanceAfter: Long,
    @Column(nullable = false, length = 50)
    val reason: String,
    @Column(name = "reference_id")
    val referenceId: Long? = null,
    // 낙서장 리팩토링(V25): 정규화 화폐 축 + typed ref (구 currency_type/reference_id 는 P1 정리)
    @Column(name = "currency_code", length = 16)
    val currencyCode: String? = null,
    @Column(name = "ref_type", length = 32)
    val refType: String? = null,
    @Column(name = "ref_key", length = 160)
    val refKey: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: LocalDateTime = LocalDateTime.now(),
)
