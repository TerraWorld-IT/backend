package com.terraworld.security

import com.terraworld.api.currency.CurrencyService
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumBackgroundRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.user.UserRole
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Sort
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * UserBootstrapService 단위 테스트 (낙서장 P1 read-cutover 후: 초기 잔액 = CurrencyService.credit 단일 SoT).
 *   - role 은 항상 USER (SEC-003)
 *   - blank email 거부 / 기존 유저 short-circuit / 동시 race DataIntegrityViolation swallow
 *   - 초기 잔액 seed: COIN 100 + RUBY 10 (credit)
 *   - terrarium 배경 deterministic 선택
 */
class UserBootstrapServiceTest {
    private val userRepository: UserRepository = mock(UserRepository::class.java)
    private val currencyService: CurrencyService = mock(CurrencyService::class.java)
    private val terrariumRepository: TerrariumRepository = mock(TerrariumRepository::class.java)
    private val terrariumBackgroundRepository: TerrariumBackgroundRepository =
        mock(TerrariumBackgroundRepository::class.java)

    private val service =
        UserBootstrapService(
            userRepository,
            currencyService,
            terrariumRepository,
            terrariumBackgroundRepository,
        )

    @Test
    fun `ensureExists short-circuits when user already exists`() {
        `when`(userRepository.existsById("cld-existing")).thenReturn(true)

        service.ensureExists("cld-existing", "user@example.com")

        verify(userRepository, never()).save(any(User::class.java))
        verify(terrariumRepository, never()).save(any(Terrarium::class.java))
    }

    @Test
    fun `ensureExists rejects blank email`() {
        assertThrows<IllegalArgumentException> { service.ensureExists("cld-new", "") }
        assertThrows<IllegalArgumentException> { service.ensureExists("cld-new", "   ") }
        verify(userRepository, never()).save(any(User::class.java))
    }

    @Test
    fun `ensureExists forces role=USER + seeds COIN 100 RUBY 10`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        service.ensureExists("cld-new", "attacker@example.com")

        val captor: ArgumentCaptor<User> = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        assertEquals(UserRole.USER, captor.value.role) // SEC-003
        // nickname 미전달 → email prefix(PII) 대신 non-PII fallback (Codex auth review).
        assertEquals("테라주민-new", captor.value.nickname)
        // 초기 잔액 = 신 substrate credit
        verify(currencyService).credit(eq("cld-new"), eq("COIN"), eq(100L), anyOrNull(), anyOrNull(), anyOrNull())
        verify(currencyService).credit(eq("cld-new"), eq("RUBY"), eq(10L), anyOrNull(), anyOrNull(), anyOrNull())
    }

    @Test
    fun `ensureExists uses passed nickname (normalized) over fallback`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        // 제어/Bidi 문자 + 공백 포함 닉네임 → NFC 정규화 + 제어문자 제거 + trim (req3/Codex).
        service.ensureExists("cld-nick", "user@example.com", "  테라‎주민왕  ")

        val captor: ArgumentCaptor<User> = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        assertEquals("테라주민왕", captor.value.nickname)
    }

    @Test
    fun `ensureExists picks deterministic lowest-id background`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java)))
            .thenReturn(listOf(background(1L, "default"), background(2L, "alt")))

        service.ensureExists("cld-new", "user@example.com")

        val terrariumCaptor: ArgumentCaptor<Terrarium> = ArgumentCaptor.forClass(Terrarium::class.java)
        verify(terrariumRepository).save(terrariumCaptor.capture())
        assertEquals(1L, terrariumCaptor.value.background.id) // SEC-021
    }

    @Test
    fun `ensureExists swallows DataIntegrityViolationException on concurrent race`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java)))
            .thenThrow(DataIntegrityViolationException("duplicate key"))

        service.ensureExists("cld-new", "user@example.com") // 전파되면 안 됨

        verify(terrariumRepository, never()).save(any(Terrarium::class.java))
    }

    @Test
    fun `ensureExists nickname truncates email local-part to 50 chars`() {
        `when`(userRepository.existsById(anyString())).thenReturn(false)
        `when`(userRepository.save(any(User::class.java))).thenAnswer { it.arguments[0] as User }
        `when`(terrariumBackgroundRepository.findAll(any(Sort::class.java))).thenReturn(emptyList())

        service.ensureExists("cld-new", "${"a".repeat(120)}@example.com")

        val captor: ArgumentCaptor<User> = ArgumentCaptor.forClass(User::class.java)
        verify(userRepository).save(captor.capture())
        assertTrue(captor.value.nickname.length <= 50)
    }

    private fun background(
        id: Long,
        name: String,
    ) = TerrariumBackground(id = id, name = name, assetUrl = "/bg/$name.png")
}
