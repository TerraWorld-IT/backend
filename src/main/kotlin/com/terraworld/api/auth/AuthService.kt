package com.terraworld.api.auth

import com.terraworld.api.auth.dto.*
import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.item.ItemRepository
import com.terraworld.domain.item.UserItem
import com.terraworld.domain.item.UserItemRepository
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import com.terraworld.security.JwtTokenProvider
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class AuthService(
    private val userRepository: UserRepository,
    private val userTokenRepository: UserTokenRepository,
    private val categoryRepository: CategoryRepository,
    private val terrariumRepository: TerrariumRepository,
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository,
    private val itemRepository: ItemRepository,
    private val userItemRepository: UserItemRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
) {

    @Transactional
    fun signup(request: SignupRequest): AuthResponse {
        if (userRepository.existsByEmail(request.email)) {
            throw BusinessException(ErrorCode.DUPLICATE_EMAIL)
        }

        val user = userRepository.save(
            User(
                email = request.email,
                passwordHash = passwordEncoder.encode(request.password),
                nickname = request.nickname,
                basicCoin = 100,
                specialCoin = 10,
            )
        )

        // 카테고리별 토큰 초기화
        val categories = categoryRepository.findAllByIsActiveTrue()
        categories.forEach { category ->
            userTokenRepository.save(UserToken(user = user, category = category, amount = 0))
        }

        // 테라리움 생성
        val defaultBg = terrariumBackgroundRepository.findById(1).orElseThrow()
        terrariumRepository.save(Terrarium(user = user, background = defaultBg))

        // 기본 아이템 지급
        listOf("plant-1", "rock-1").forEach { slug ->
            itemRepository.findBySlug(slug).ifPresent { item ->
                userItemRepository.save(UserItem(user = user, item = item))
            }
        }

        return AuthResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
            accessToken = jwtTokenProvider.generateAccessToken(user.id, user.email),
            refreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.email),
        )
    }

    fun login(request: LoginRequest): AuthResponse {
        val user = userRepository.findByEmail(request.email)
            .orElseThrow { BusinessException(ErrorCode.INVALID_CREDENTIALS) }

        if (!passwordEncoder.matches(request.password, user.passwordHash)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }

        return AuthResponse(
            userId = user.id,
            email = user.email,
            nickname = user.nickname,
            accessToken = jwtTokenProvider.generateAccessToken(user.id, user.email),
            refreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.email),
        )
    }

    fun refresh(request: RefreshRequest): TokenResponse {
        if (!jwtTokenProvider.validateToken(request.refreshToken)) {
            throw BusinessException(ErrorCode.INVALID_TOKEN)
        }
        val userId = jwtTokenProvider.getUserIdFromToken(request.refreshToken)
        val user = userRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.USER_NOT_FOUND) }

        return TokenResponse(
            accessToken = jwtTokenProvider.generateAccessToken(user.id, user.email),
            refreshToken = jwtTokenProvider.generateRefreshToken(user.id, user.email),
        )
    }

    fun logout() {
        // TODO: Redis blacklist
    }
}
