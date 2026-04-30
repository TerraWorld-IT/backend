package com.terraworld.api.category

import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@Tag(name = "Category", description = "카테고리 API")
@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(
    private val categoryRepository: CategoryRepository,
) {
    @Operation(summary = "카테고리 목록 조회")
    @GetMapping
    fun getCategories(): Map<String, List<CategoryResponse>> {
        val categories = categoryRepository.findAllByIsActiveTrue()
        return mapOf("categories" to categories.map { it.toResponse() })
    }

    private fun Category.toResponse() =
        CategoryResponse(
            id = id,
            name = name,
            iconUrl = iconUrl,
            color = color,
            tokenName = tokenName,
            emoji = emoji,
            baseCoinReward = baseCoinReward,
            baseTokenReward = baseTokenReward,
            dailyLimit = dailyLimit,
        )
}

data class CategoryResponse(
    val id: Long,
    val name: String,
    val iconUrl: String?,
    val color: String,
    val tokenName: String,
    val emoji: String?,
    val baseCoinReward: Int,
    val baseTokenReward: Int,
    val dailyLimit: Int,
)
