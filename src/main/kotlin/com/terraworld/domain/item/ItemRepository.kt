package com.terraworld.domain.item

import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional

interface ItemRepository : JpaRepository<Item, Long> {
    fun findAllByIsActiveTrue(): List<Item>
    fun findAllByIsActiveTrueAndCategoryId(categoryId: Long): List<Item>
    fun findAllByIsActiveTrueAndRarity(rarity: Rarity): List<Item>
    fun findAllByIsActiveTrueAndLayout(layout: ItemLayout): List<Item>
    fun findBySlug(slug: String): Optional<Item>
}

interface UserItemRepository : JpaRepository<UserItem, Long> {
    fun findAllByUserId(userId: String): List<UserItem>
    fun existsByUserIdAndItemId(userId: String, itemId: Long): Boolean
}
