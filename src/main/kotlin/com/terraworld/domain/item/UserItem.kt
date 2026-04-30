package com.terraworld.domain.item

import com.terraworld.domain.user.User
import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "user_items", uniqueConstraints = [UniqueConstraint(columnNames = ["user_id", "item_id"])])
class UserItem(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    val user: User,
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "item_id", nullable = false)
    val item: Item,
    @Column(nullable = false)
    val quantity: Int = 1,
    @Column(name = "acquired_at", nullable = false, updatable = false)
    val acquiredAt: LocalDateTime = LocalDateTime.now(),
)
