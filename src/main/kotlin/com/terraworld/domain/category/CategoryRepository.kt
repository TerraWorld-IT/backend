package com.terraworld.domain.category

import org.springframework.data.jpa.repository.JpaRepository

interface CategoryRepository : JpaRepository<Category, Long> {
    fun findAllByIsActiveTrue(): List<Category>
}
