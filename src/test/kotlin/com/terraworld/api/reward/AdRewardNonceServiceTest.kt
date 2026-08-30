package com.terraworld.api.reward

import com.terraworld.common.exception.BusinessException
import com.terraworld.common.exception.ErrorCode
import com.terraworld.domain.reward.AdRewardNonceInbox
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Duration
import java.time.LocalDateTime
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AdRewardNonceServiceTest {
    private val repository: AdRewardNonceInboxRepository = mock()

    @Test
    fun `서버 nonce 발급은 고정 TTL과 사용자 용도를 바인딩한다`() {
        val service = service("shadow")
        whenever(repository.findActiveIssued(eq("user-1"), eq(AdRewardNonceInbox.PURPOSE_AD_REWARD), any()))
            .thenReturn(emptyList())
        whenever(repository.saveAndFlush(any<AdRewardNonceInbox>())).thenAnswer { it.arguments[0] }

        val issued = service.issue("user-1", AdRewardNonceInbox.PURPOSE_AD_REWARD)

        assertEquals(AdRewardNonceInbox.PURPOSE_AD_REWARD, issued.purpose)
        assertEquals(AdRewardNonceInbox.STATUS_PENDING, issued.status)
        assertTrue(issued.nonce.length in 16..64)
        verify(repository).acquireIssueLock("ad-reward-nonce-issue|user-1|AD_REWARD")
        val saved = argumentCaptor<AdRewardNonceInbox>()
        verify(repository).saveAndFlush(saved.capture())
        assertEquals(
            AdRewardNonceService.NONCE_TTL,
            Duration.between(saved.firstValue.createdAt, requireNotNull(saved.firstValue.expiresAt)),
        )
    }

    @Test
    fun `유효한 활성 nonce 가 있으면 새 행을 만들지 않고 재사용한다`() {
        val service = service("shadow")
        val existing = issuedNonce(status = AdRewardNonceInbox.STATUS_VERIFIED)
        whenever(repository.findActiveIssued(eq("user-1"), eq(AdRewardNonceInbox.PURPOSE_AD_REWARD), any()))
            .thenReturn(listOf(existing))

        val issued = service.issue("user-1", AdRewardNonceInbox.PURPOSE_AD_REWARD)

        assertEquals(existing.nonce, issued.nonce)
        assertEquals(AdRewardNonceInbox.STATUS_VERIFIED, issued.status)
        verify(repository, never()).saveAndFlush(any<AdRewardNonceInbox>())
    }

    @Test
    fun `legacy 는 클라이언트 nonce 를 즉시 소비한다`() {
        val service = service("legacy")
        whenever(repository.insertLegacyIfAbsent(any(), any(), any(), any())).thenReturn(1)

        service.consume("user-1", VALID_NONCE, AdRewardNonceInbox.PURPOSE_AD_REWARD)

        verify(repository).insertLegacyIfAbsent(
            VALID_NONCE,
            "user-1",
            AdRewardNonceInbox.SOURCE_CLIENT,
            AdRewardNonceInbox.PURPOSE_AD_REWARD,
        )
        verify(repository, never()).consumeVerifiedIssued(any(), any(), any(), any())
    }

    @Test
    fun `authoritative 는 임의 클라이언트 nonce 를 거절한다`() {
        val service = service("authoritative")
        whenever(repository.consumeVerifiedIssued(any(), any(), any(), any())).thenReturn(0)
        whenever(repository.findById(VALID_NONCE)).thenReturn(Optional.empty())

        val error =
            assertThrows<BusinessException> {
                service.consume("user-1", VALID_NONCE, AdRewardNonceInbox.PURPOSE_AD_REWARD)
            }

        assertEquals(ErrorCode.INVALID_INPUT, error.errorCode)
        verify(repository, never()).insertLegacyIfAbsent(any(), any(), any(), any())
    }

    @Test
    fun `authoritative 는 VERIFIED 서버 nonce 를 한 번만 소비한다`() {
        val service = service("authoritative")
        whenever(
            repository.consumeVerifiedIssued(
                eq(VALID_NONCE),
                eq("user-1"),
                eq(AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE),
                any(),
            ),
        ).thenReturn(1)

        service.consume("user-1", VALID_NONCE, AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE)

        verify(repository).consumeVerifiedIssued(
            eq(VALID_NONCE),
            eq("user-1"),
            eq(AdRewardNonceInbox.PURPOSE_GROWTH_REVIVE),
            any(),
        )
    }

    @Test
    fun `이미 소비된 서버 nonce 재사용은 409 오류로 분류한다`() {
        val service = service("authoritative")
        whenever(repository.consumeVerifiedIssued(any(), any(), any(), any())).thenReturn(0)
        whenever(repository.findById(VALID_NONCE)).thenReturn(
            Optional.of(
                issuedNonce(
                    status = AdRewardNonceInbox.STATUS_CONSUMED,
                    consumedAt = LocalDateTime.now(),
                ),
            ),
        )

        val error =
            assertThrows<BusinessException> {
                service.consume("user-1", VALID_NONCE, AdRewardNonceInbox.PURPOSE_AD_REWARD)
            }

        assertEquals(ErrorCode.NONCE_ALREADY_CONSUMED, error.errorCode)
    }

    @Test
    fun `shadow SSV 는 custom_data 를 서버 nonce 와 결합한다`() {
        val service = service("shadow")
        whenever(repository.findByTransactionId("tx-1")).thenReturn(null)
        whenever(repository.verifyIssued(eq(VALID_NONCE), eq("user-1"), eq("tx-1"), any())).thenReturn(1)
        whenever(repository.findById(VALID_NONCE)).thenReturn(Optional.of(issuedNonce()))

        val outcome = service.recordVerifiedSsv("user-1", "tx-1", VALID_NONCE)

        assertEquals(SsvNonceResult.VERIFIED, outcome.result)
        assertEquals(AdRewardNonceInbox.PURPOSE_AD_REWARD, outcome.purpose)
        verify(repository, never()).insertDetachedSsvIfAbsent(any(), any(), any())
    }

    @Test
    fun `authoritative SSV 의 미발급 custom_data 는 지급 가능 상태가 되지 않는다`() {
        val service = service("authoritative")
        whenever(repository.findByTransactionId("tx-1")).thenReturn(null)
        whenever(repository.verifyIssued(eq(VALID_NONCE), eq("user-1"), eq("tx-1"), any())).thenReturn(0)
        whenever(repository.insertDetachedSsvIfAbsent(any(), eq("tx-1"), eq("user-1"))).thenReturn(1)

        val outcome = service.recordVerifiedSsv("user-1", "tx-1", VALID_NONCE)

        assertEquals(SsvNonceResult.UNMATCHED, outcome.result)
    }

    @Test
    fun `같은 SSV transaction 재호출은 nonce 를 다시 검증하지 않는다`() {
        val service = service("authoritative")
        whenever(repository.findByTransactionId("tx-1")).thenReturn(
            issuedNonce(transactionId = "tx-1", status = AdRewardNonceInbox.STATUS_VERIFIED),
        )

        val outcome = service.recordVerifiedSsv("user-1", "tx-1", VALID_NONCE)

        assertEquals(SsvNonceResult.DUPLICATE, outcome.result)
        assertEquals(AdRewardNonceInbox.PURPOSE_AD_REWARD, outcome.purpose)
        verify(repository, never()).verifyIssued(any(), any(), any(), any())
    }

    @Test
    fun `nonce 형식과 purpose 허용 목록을 서비스 경계에서 강제한다`() {
        val service = service("legacy")

        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> {
                service.consume("user-1", "short", AdRewardNonceInbox.PURPOSE_AD_REWARD)
            }.errorCode,
        )
        assertEquals(
            ErrorCode.INVALID_INPUT,
            assertThrows<BusinessException> {
                service.issue("user-1", "UNKNOWN")
            }.errorCode,
        )
    }

    private fun service(mode: String) = AdRewardNonceService(repository, AdRewardPolicy(mode))

    private fun issuedNonce(
        transactionId: String? = null,
        status: String = AdRewardNonceInbox.STATUS_PENDING,
        consumedAt: LocalDateTime? = null,
    ) = AdRewardNonceInbox(
        nonce = VALID_NONCE,
        userId = "user-1",
        source = AdRewardNonceInbox.SOURCE_SERVER,
        transactionId = transactionId,
        purpose = AdRewardNonceInbox.PURPOSE_AD_REWARD,
        status = status,
        expiresAt = LocalDateTime.now().plusMinutes(10),
        verifiedAt = transactionId?.let { LocalDateTime.now() },
        consumedAt = consumedAt,
    )

    companion object {
        private const val VALID_NONCE = "550e8400-e29b-41d4-a716-446655440000"
    }
}
