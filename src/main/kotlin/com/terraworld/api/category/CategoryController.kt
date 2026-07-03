package com.terraworld.api.category

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.security.AuthenticatedUser
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import io.terraworld.api.api.CategoryApi
import io.terraworld.api.model.CategoryListResponse
import io.terraworld.api.model.CategoryResponse
import io.terraworld.api.model.CreateCategoryRequest
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * CategoryApi (3 endpoints) implement. local DTO 들 (CategoryResponse,
 * CreateCategoryRequest) 은 generated 와 동등하므로 제거하고 generated 직접
 * 사용.
 *
 * 응답 status code 는 인터페이스 default (CREATED 201, NO_CONTENT 204) 를
 * ResponseEntity 로 명시 적용 — generated 인터페이스는 status code 를
 * 강제하지 않음.
 */
@Tag(name = "Category", description = "카테고리 API (시스템 4종 + 사용자 커스텀)")
@RestController
@RequestMapping("/api/v1")
class CategoryController(
    private val categoryRepository: CategoryRepository,
) : CategoryApi {
    companion object {
        const val CUSTOM_CATEGORY_LIMIT = 10
        const val DEFAULT_CUSTOM_COIN_REWARD = 20
        const val DEFAULT_CUSTOM_TOKEN_REWARD = 10
        const val DEFAULT_CUSTOM_DAILY_LIMIT = 5
    }

    /**
     * 카테고리 목록 — 미인증 시 시스템 4종, 인증 시 시스템 4종 + 본인 커스텀.
     */
    @Operation(summary = "카테고리 목록 조회")
    override fun listCategories(): ResponseEntity<CategoryListResponse> {
        val system = categoryRepository.findAllByIsActiveTrueAndIsCustomFalse()
        val mine =
            currentUserIdOrNull()
                ?.let { categoryRepository.findAllByOwnerUserIdAndIsActiveTrue(it) }
                ?: emptyList()
        return ResponseEntity.ok(
            CategoryListResponse(categories = (system + mine).map { it.toApi() }),
        )
    }

    @Operation(summary = "커스텀 카테고리 생성")
    @Transactional
    override fun createCustomCategory(
        @Valid createCategoryRequest: CreateCategoryRequest,
    ): ResponseEntity<CategoryResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val count = categoryRepository.countByOwnerUserIdAndIsActiveTrue(userId)
        if (count >= CUSTOM_CATEGORY_LIMIT) {
            throw BusinessException(ErrorCode.CATEGORY_LIMIT_EXCEEDED)
        }
        if (categoryRepository.existsByNameAndIsCustomFalse(createCategoryRequest.name)) {
            throw BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATE, "시스템 카테고리와 이름이 같습니다")
        }
        if (categoryRepository.existsByNameAndOwnerUserIdAndIsActiveTrue(createCategoryRequest.name, userId)) {
            throw BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATE)
        }

        val saved =
            categoryRepository.save(
                Category(
                    name = createCategoryRequest.name,
                    color = createCategoryRequest.color,
                    tokenName = createCategoryRequest.tokenName,
                    emoji = createCategoryRequest.emoji,
                    baseCoinReward = DEFAULT_CUSTOM_COIN_REWARD,
                    baseTokenReward = DEFAULT_CUSTOM_TOKEN_REWARD,
                    dailyLimit = DEFAULT_CUSTOM_DAILY_LIMIT,
                    isActive = true,
                    isCustom = true,
                    ownerUserId = userId,
                ),
            )

        // 낙서장 P1: 커스텀 카테고리 토큰 pre-create 제거 — 화폐는 신 substrate(user_currency_balances)
        // 이며 커스텀 카테고리는 코인만(정책 #1). 별도 토큰 currency 미노출.
        return ResponseEntity
            .status(HttpStatus.CREATED)
            .body(saved.toApi())
    }

    @Operation(summary = "커스텀 카테고리 삭제 (soft)")
    @Transactional
    override fun deleteCategory(categoryId: Long): ResponseEntity<Unit> {
        val userId = SecurityUtil.getCurrentUserId()
        val category =
            categoryRepository
                .findById(categoryId)
                .orElseThrow { BusinessException(ErrorCode.CATEGORY_NOT_FOUND) }
        if (!category.isCustom) throw BusinessException(ErrorCode.CATEGORY_PROTECTED)
        if (category.ownerUserId != userId) throw BusinessException(ErrorCode.FORBIDDEN)

        category.isActive = false
        categoryRepository.save(category)
        return ResponseEntity.noContent().build()
    }

    private fun currentUserIdOrNull(): String? =
        runCatching {
            val auth = SecurityContextHolder.getContext().authentication
            if (auth != null && auth.isAuthenticated && auth.principal is AuthenticatedUser) {
                (auth.principal as AuthenticatedUser).id
            } else {
                null
            }
        }.getOrNull()

    private fun Category.toApi() =
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
            isCustom = isCustom,
            ownerUserId = ownerUserId,
        )
}
