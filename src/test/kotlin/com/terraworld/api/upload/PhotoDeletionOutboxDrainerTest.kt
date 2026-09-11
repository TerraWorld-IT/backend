package com.terraworld.api.upload

import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.upload.PhotoDeletionOutbox
import com.terraworld.domain.upload.PhotoDeletionOutboxRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.time.LocalDateTime
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhotoDeletionOutboxDrainerTest {
    private val repository = FakeOutbox()
    private val storage: R2PhotoStorage = mock()
    private val records: RecordRepository = mock()
    private val drainer = PhotoDeletionOutboxDrainer(repository, storage, records)

    @BeforeEach
    fun setUp() {
        whenever(storage.isEnabled()).thenReturn(true)
        whenever(storage.ownsPublicUrl(any())).thenReturn(true)
    }

    @Test
    fun `성공한 사진은 outbox에서 삭제한다`() {
        repository.save(PhotoDeletionOutbox("photo"))

        drainer.drain()

        verify(storage).delete("photo")
        assertTrue(repository.all().isEmpty())
    }

    @Test
    fun `대기열 등록 후 다른 사용자 기록이 참조한 사진은 보존하고 outbox에서 삭제한다`() {
        val photo = repository.save(PhotoDeletionOutbox("shared-photo", attempts = 1))
        // 재시도를 기다리는 동안 생존 사용자의 기록에서 사진을 참조하게 되었다.
        whenever(records.existsByPhotoUrl(photo.publicUrl)).thenReturn(true)

        drainer.drain()

        verify(records).existsByPhotoUrl(photo.publicUrl)
        verify(storage, never()).delete(any())
        assertTrue(repository.all().isEmpty())
        assertEquals(1, photo.attempts)
    }

    @Test
    fun `실패한 사진은 횟수와 시각을 갱신하고 다음 사진을 처리한다`() {
        val failed = repository.save(PhotoDeletionOutbox("failed"))
        repository.save(PhotoDeletionOutbox("next"))
        doThrow(IllegalStateException("비공개 SDK 오류 본문")).whenever(storage).delete("failed")
        val before = LocalDateTime.now()

        drainer.drain()

        assertEquals(listOf(failed), repository.all())
        assertEquals(1, failed.attempts)
        assertNotNull(failed.lastAttemptAt)
        assertTrue(!failed.lastAttemptAt!!.isBefore(before))
        verify(storage).delete("next")
    }

    @Test
    fun `열 번째 실패는 보존하고 다음 배치에서 재시도하지 않는다`() {
        val failed = repository.save(PhotoDeletionOutbox("failed", attempts = 9))
        doThrow(IllegalStateException()).whenever(storage).delete("failed")

        drainer.drain()
        drainer.drain()

        assertEquals(10, failed.attempts)
        assertEquals(listOf(failed), repository.all())
        verify(storage).delete("failed")
    }

    @Test
    fun `배치는 최대 오십 건만 조회하고 한도 초과 행은 남긴다`() {
        repeat(51) { repository.save(PhotoDeletionOutbox("photo-$it")) }
        val exhausted = repository.save(PhotoDeletionOutbox("exhausted", attempts = 10))

        drainer.drain()

        assertEquals(50, repository.requestedPage!!.pageSize)
        assertEquals(0, repository.requestedPage!!.pageNumber)
        assertEquals(10, repository.requestedMax)
        assertEquals(2L, repository.count())
        assertTrue(repository.all().contains(exhausted))
        verify(storage, never()).delete("exhausted")
    }

    @Test
    fun `R2 미설정은 무동작 성공으로 간주하지 않는다`() {
        val photo = repository.save(PhotoDeletionOutbox("photo"))
        whenever(storage.isEnabled()).thenReturn(false)

        drainer.drain()

        assertEquals(listOf(photo), repository.all())
        assertEquals(1, photo.attempts)
        verify(storage, never()).delete(any())
    }

    @Test
    fun `소유 URL 설정이 달라져도 삭제 대상을 잃지 않는다`() {
        val photo = repository.save(PhotoDeletionOutbox("photo"))
        whenever(storage.ownsPublicUrl("photo")).thenReturn(false)

        drainer.drain()

        assertEquals(listOf(photo), repository.all())
        assertEquals(1, photo.attempts)
        verify(storage, never()).delete(any())
    }

    @Test
    fun `실패 기록 DB 오류도 다음 사진 처리를 막지 않는다`() {
        repository.save(PhotoDeletionOutbox("failed"))
        repository.save(PhotoDeletionOutbox("next"))
        repository.failUpdates = true
        doThrow(IllegalStateException()).whenever(storage).delete("failed")

        drainer.drain()

        assertEquals(listOf("failed"), repository.all().map { it.publicUrl })
        verify(storage).delete("next")
    }

    private class FakeOutbox :
        FakeJpaRepository<PhotoDeletionOutbox, String>(),
        PhotoDeletionOutboxRepository {
        var requestedPage: Pageable? = null
        var requestedMax: Int? = null
        var failUpdates = false

        override fun extractId(entity: PhotoDeletionOutbox): String = entity.publicUrl

        override fun findByAttemptsLessThanOrderByCreatedAtAscPublicUrlAsc(
            maxAttempts: Int,
            pageable: Pageable,
        ): List<PhotoDeletionOutbox> {
            requestedPage = pageable
            requestedMax = maxAttempts
            return all()
                .filter { it.attempts < maxAttempts }
                .sortedWith(compareBy({ it.createdAt }, { it.publicUrl }))
                .take(pageable.pageSize)
        }

        override fun deleteCompleted(publicUrl: String): Int = if (store.remove(publicUrl) != null) 1 else 0

        override fun recordFailure(
            publicUrl: String,
            now: LocalDateTime,
            maxAttempts: Int,
        ): Int {
            check(!failUpdates)
            val photo = store[publicUrl]?.takeIf { it.attempts < maxAttempts } ?: return 0
            photo.attempts++
            photo.lastAttemptAt = now
            return 1
        }
    }
}
