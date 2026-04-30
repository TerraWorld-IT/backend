package com.terraworld.domain.user

import com.terraworld.domain.category.Category
import jakarta.persistence.*

@Entity
@Table(name = "user_tokens", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "category_id"])])
class UserToken(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id", nullable = false)
    val category: Category,
    @Column(nullable = false)
    var amount: Long = 0,
    @Version
    @Column(nullable = false)
    var version: Long = 0,
)
