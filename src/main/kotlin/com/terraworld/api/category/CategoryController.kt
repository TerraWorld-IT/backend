package com.terraworld.api.category

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.security.SecurityUtil
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@Tag(name = "Category", description = "카테고리 API (시스템 4종 + 사용자 커스텀)")
@RestController
@RequestMapping("/api/v1/categories")
class CategoryController(
    private val categoryRepository: CategoryRepository,
    private val userTokenRepository: UserTokenRepository,
    private val userRepository: UserRepository,
) {
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
    @GetMapping
    fun getCategories(): Map<String, List<CategoryResponse>> {
        val system = categoryRepository.findAllByIsActiveTrueAndIsCustomFalse()
        val mine =
            currentUserIdOrNull()
                ?.let { categoryRepository.findAllByOwnerUserIdAndIsActiveTrue(it) }
                ?: emptyList()
        return mapOf("categories" to (system + mine).map { it.toResponse() })
    }

    @Operation(summary = "커스텀 카테고리 생성")
    @PostMapping
    @Transactional
    fun createCustom(
        @Valid @RequestBody request: CreateCategoryRequest,
    ): org.springframework.http.ResponseEntity<CategoryResponse> {
        val userId = SecurityUtil.getCurrentUserId()
        val count = categoryRepository.countByOwnerUserIdAndIsActiveTrue(userId)
        if (count >= CUSTOM_CATEGORY_LIMIT) {
            throw BusinessException(ErrorCode.CATEGORY_LIMIT_EXCEEDED)
        }
        if (categoryRepository.existsByNameAndIsCustomFalse(request.name)) {
            throw BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATE, "시스템 카테고리와 이름이 같습니다")
        }
        if (categoryRepository.existsByNameAndOwnerUserIdAndIsActiveTrue(request.name, userId)) {
            throw BusinessException(ErrorCode.CATEGORY_NAME_DUPLICATE)
        }

        val saved =
            categoryRepository.save(
                Category(
                    name = request.name,
                    color = request.color,
                    tokenName = request.tokenName,
                    emoji = request.emoji,
                    baseCoinReward = DEFAULT_CUSTOM_COIN_REWARD,
                    baseTokenReward = DEFAULT_CUSTOM_TOKEN_REWARD,
                    dailyLimit = DEFAULT_CUSTOM_DAILY_LIMIT,
                    isActive = true,
                    isCustom = true,
                    ownerUserId = userId,
                ),
            )

        // UserToken 행을 amount=0 으로 pre-create — WalletBar / CurrencyResponse.categoryTokens
        // 에 즉시 노출하기 위함. 첫 기록까지 row 가 없으면 사용자가 "내 카테고리는 있는데
        // 토큰은 안 보임" 으로 혼란을 겪는다.
        val user = userRepository.findById(userId).orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }
        userTokenRepository.save(UserToken(user = user, category = saved, amount = 0))

        return org.springframework.http.ResponseEntity
            .status(HttpStatus.CREATED)
            .body(saved.toResponse())
    }

    @Operation(summary = "커스텀 카테고리 삭제 (soft)")
    @DeleteMapping("/{categoryId}")
    @Transactional
    fun deleteCustom(
        @PathVariable categoryId: Long,
    ): org.springframework.http.ResponseEntity<Void> {
        val userId = SecurityUtil.getCurrentUserId()
        val category =
            categoryRepository.findById(categoryId).orElseThrow {
                BusinessException(ErrorCode.CATEGORY_NOT_FOUND)
            }
        if (!category.isCustom) throw BusinessException(ErrorCode.CATEGORY_PROTECTED)
        if (category.ownerUserId != userId) throw BusinessException(ErrorCode.FORBIDDEN)

        category.isActive = false
        categoryRepository.save(category)
        return org.springframework.http.ResponseEntity
            .noContent()
            .build()
    }

    private fun currentUserIdOrNull(): String? =
        runCatching {
            val auth = SecurityContextHolder.getContext().authentication
            if (auth != null && auth.isAuthenticated && auth.principal is com.terraworld.security.AuthenticatedUser) {
                (auth.principal as com.terraworld.security.AuthenticatedUser).id
            } else {
                null
            }
        }.getOrNull()

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
            isCustom = isCustom,
            ownerUserId = ownerUserId,
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
    val isCustom: Boolean = false,
    val ownerUserId: String? = null,
)

data class CreateCategoryRequest(
    @field:Size(min = 1, max = 20)
    val name: String,
    @field:Pattern(regexp = "^#[0-9A-Fa-f]{6}$")
    val color: String,
    @field:Size(min = 1, max = 20)
    val tokenName: String,
    @field:Size(max = 8)
    val emoji: String? = null,
)
