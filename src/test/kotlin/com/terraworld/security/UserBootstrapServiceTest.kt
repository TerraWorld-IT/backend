package com.terraworld.security

import com.terraworld.domain.category.Category
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import com.terraworld.domain.user.UserToken
import com.terraworld.domain.user.UserTokenRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyIterable
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for [UserBootstrapService]. Covers the security invariants
 * flagged in SEC-003 / SEC-011 / SEC-020 / SEC-021:
 *   - role is always USER on create regardless of intent
 *   - blank email is rejected
 *   - existing user short-circuits (idempotency)
 *   - concurrent race swallows DataIntegrityViolationException
 *   - user tokens are batched (saveAll)
 *   - terrarium background uses deterministic ordering
 */
class UserBootstrapServiceTest {
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val userTokenRepository: UserTokenRepository = mock(UserTokenRepository::class.java)
    private val categoryRepository: CategoryRepository = mock(CategoryRepository::class.java)
    private val terrariumRepository: TerrariumRepository = mock(TerrariumRepository::class.java)
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository =
        mock(TerrariumBackgroundRepository::class.java)

    private val service =
        UserBootstrapService(
            userRepository,
            userTokenRepository,
            categoryRepository,
            terrariumRepository,
            terrariumBackgroundRepository,
        )

    @Test
    fun `ensureExists short-circuits when user already exists`() {
        `when`(userRepository.existsById("cld-existing")).thenReturn(true)

        service.ensureExists("cld-existing", "user@example.com")

        verify(userRepository, never()).save(any(User::class.java))
        verify(userTokenRepository, never()).saveAll(anyIterable())
        verify(terrariumRepository, never()).save(any(Terrarium::class.java))
    }

    @Test
    fun `ensureExists rejects blank email`() {
        assertThrows<IllegalArgumentException> {
            service.ensureExists("cld-new", "")
        }
        assertThrows<IllegalArgumentException> {
            service.ensureExists("cld-new", "   ")
        }
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun `ensureExists forces role=USER on create`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(categoryRepository.findAll()).thenReturn(emptyList())
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        service.ensureExists("cld-new", "attacker@example.com")

        val captor: ArgumentCaptor<User> = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        // SEC-003: role always defaults to USER on create. No override path.
        assertEquals(UserRole.USER, captor.value.role)
        assertEquals("attacker", captor.value.nickname)
        assertEquals(100L, captor.value.basicCoin)
    }

    @Test
    fun `ensureExists batches user tokens and picks deterministic background`() {
        val walking = category(1L, "walking")
        val reading = category(2L, "reading")
        val background1 = background(1L, "default")
        val background2 = background(2L, "alt")

        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(categoryRepository.findAll()).thenReturn(listOf(walking, reading))
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java)))
            .thenReturn(listOf(background1, background2))

        service.ensureExists("cld-new", "user@example.com")

        // SEC-020: batched insert (saveAll), not N+1.
        @Suppress("UNCHECKED_CAST")
        val tokenCaptor = ArgumentCaptor.forClass(List::class.java) as ArgumentCaptor<List<UserToken>>
        verify(userTokenRepository, times(1)).saveAll(tokenCaptor.capture())
        assertEquals(2, tokenCaptor.value.size)

        val terrariumCaptor: ArgumentCaptor<Terrarium> = ArgumentCaptor.forClass(Terrarium::class.java)
        verify(terrariumRepository).save(terrariumCaptor.capture())
        // SEC-021: deterministic — always the lowest-id background.
        assertEquals(1L, terrariumCaptor.value.background.id)
    }

    @Test
    fun `ensureExists swallows DataIntegrityViolationException on concurrent race`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate key"))

        // Should NOT propagate — the winner already provisioned the row.
        service.ensureExists("cld-new", "user@example.com")

        verify(userTokenRepository, never()).saveAll(anyIterable())
        verify(terrariumRepository, never()).save(any(Terrarium::class.java))
    }

    @Test
    fun `ensureExists skips terrarium when no backgrounds are seeded`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(categoryRepository.findAll()).thenReturn(emptyList())
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        service.ensureExists("cld-new", "user@example.com")

        verify(terrariumRepository, never()).save(any(Terrarium::class.java))
    }

    @Test
    fun `ensureExists nickname truncates email local-part to 50 chars`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(categoryRepository.findAll()).thenReturn(emptyList())
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        val longLocal = "a".repeat(120)
        service.ensureExists("cld-new", "$longLocal@example.com")

        val captor: ArgumentCaptor<User> = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        assertTrue(captor.value.nickname.length <= 50)
    }

    private fun category(
        id: Long,
        name: String,
    ) = Category(
        id = id,
        name = name,
        color = "#000000",
        tokenName = "${name}Token",
        baseCoinReward = 10,
        baseTokenReward = 5,
        dailyLimit = 5,
    )

    private fun background(
        id: Long,
        name: String,
    ) = TerrariumBackground(
        id = id,
        name = name,
        assetUrl = "/bg/$name.png",
    )
}
