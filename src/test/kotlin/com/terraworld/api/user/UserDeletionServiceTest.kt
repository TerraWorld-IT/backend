package com.terraworld.api.user

import com.terraworld.api.upload.PhotoDeletionOutboxDrainer
import com.terraworld.api.upload.R2PhotoStorage
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
import com.terraworld.domain.record.HabitPairRequestRepository
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import com.terraworld.domain.upload.PhotoDeletionOutbox
import com.terraworld.domain.upload.PhotoDeletionOutboxRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.userdevice.UserDeviceRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.verifyNoMoreInteractions
import org.mockito.kotlin.whenever
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.Optional
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UserDeletionServiceTest {
    private val users = FakeUsers()
    private val records: RecordRepository = mock()
    private val devices: UserDeviceRepository = mock()
    private val trackers: HabitTrackerRepository = mock()
    private val nonces: AdRewardNonceInboxRepository = mock()
    private val exchanges: ExchangeDailyUsageRepository = mock()
    private val photos: R2PhotoStorage = mock()
    private val categories: CategoryRepository = mock()
    private val requests: HabitPairRequestRepository = mock()
    private val outbox: PhotoDeletionOutboxRepository = mock()
    private val drainer = PhotoDeletionOutboxDrainer(outbox, photos, records)
    private val service = UserDeletionService(users, records, devices, trackers, nonces, exchanges, photos, categories, requests, outbox, drainer)

    @BeforeEach
    fun setUp() {
        TransactionSynchronizationManager.initSynchronization()
        users.save(User("deleted-user", "탈퇴"))
        users.save(User("other-user", "유지"))
        whenever(records.findPhotoUrlsByUserId("deleted-user")).thenReturn(listOf("owned-photo", "owned-photo", "external-photo"))
        whenever(photos.ownsPublicUrl("owned-photo")).thenReturn(true)
        whenever(photos.isEnabled()).thenReturn(true)
        whenever(outbox.findById(any())).thenReturn(Optional.empty())
        whenever(outbox.save(any<PhotoDeletionOutbox>())).thenAnswer { it.arguments[0] }
    }

    @AfterEach
    fun tearDown() {
        TransactionSynchronizationManager.clearSynchronization()
    }

    @Test
    fun `처분표의 네 저장소를 정리하고 사용자를 삭제하며 R2는 커밋까지 기다린다`() {
        service.deleteUser("deleted-user")

        assertFalse(users.existsById("deleted-user"))
        assertTrue(users.existsById("other-user"))
        inOrder(records, devices, trackers, nonces, exchanges) {
            verify(records).findPhotoUrlsByUserId("deleted-user")
            verify(records).findPhotoUrlsReferencedByOtherUsers("deleted-user", listOf("owned-photo"))
            verify(devices).deleteAllByUserId("deleted-user")
            verify(trackers).deleteAllByUserId("deleted-user")
            verify(nonces).deleteAllByUserId("deleted-user")
            verify(exchanges).deleteAllByUserId("deleted-user")
            verify(devices).deleteAllByUserId("deleted-user")
            verify(trackers).deleteAllByUserId("deleted-user")
            verify(nonces).deleteAllByUserId("deleted-user")
            verify(exchanges).deleteAllByUserId("deleted-user")
        }
        verifyNoMoreInteractions(records, devices, trackers, nonces, exchanges)
        // 보존 원장은 서비스 의존성 자체에 없으므로 삭제·변경 경로가 없다.
        verify(photos, never()).delete(any())
        verify(outbox).save(argThat<PhotoDeletionOutbox> { publicUrl == "owned-photo" && attempts == 0 && lastAttemptAt == null })
        verify(outbox, never()).save(argThat<PhotoDeletionOutbox> { publicUrl == "external-photo" })
        verify(outbox, never()).deleteCompleted(any())
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        verify(photos).delete("owned-photo")
        verify(outbox).deleteCompleted("owned-photo")
        verify(photos, never()).delete("external-photo")
    }

    @Test
    fun `다른 사용자 기록이 참조하는 사진은 삭제하지 않는다`() {
        whenever(records.findPhotoUrlsReferencedByOtherUsers("deleted-user", listOf("owned-photo"))).thenReturn(listOf("owned-photo"))
        service.deleteUser("deleted-user")
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertFalse(users.existsById("deleted-user"))
        verify(photos, never()).delete(any())
        verifyNoInteractions(outbox)
    }

    @Test
    fun `이미 없는 사용자 재삭제는 DB 변경과 R2 호출 없이 성공한다`() {
        service.deleteUser("deleted-user")
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        TransactionSynchronizationManager.clearSynchronization()
        TransactionSynchronizationManager.initSynchronization()
        clearInvocations(records, devices, trackers, nonces, exchanges, photos, categories, requests, outbox)

        service.deleteUser("deleted-user")
        service.deleteUser("never-existed")

        assertFalse(users.existsById("deleted-user"))
        assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty())
        verifyNoInteractions(records, devices, trackers, nonces, exchanges, photos, categories, requests, outbox)
    }

    @Test
    fun `롤백에서는 R2를 삭제하지 않는다`() {
        service.deleteUser("deleted-user")
        TransactionSynchronizationManager.getSynchronizations().forEach {
            it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
        }
        verify(photos, never()).delete(any())
    }

    @Test
    fun `R2 실패가 커밋된 사용자 삭제를 되돌리거나 다음 사진 삭제를 막지 않는다`() {
        whenever(records.findPhotoUrlsByUserId("deleted-user")).thenReturn(listOf("owned-photo", "next-photo"))
        whenever(photos.ownsPublicUrl("next-photo")).thenReturn(true)
        doThrow(IllegalStateException("test failure")).whenever(photos).delete("owned-photo")
        service.deleteUser("deleted-user")
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }
        assertFalse(users.existsById("deleted-user"))
        verify(photos).delete("next-photo")
        verify(outbox).recordFailure(eq("owned-photo"), any(), eq(10))
        verify(outbox, never()).deleteCompleted("owned-photo")
        verify(outbox).deleteCompleted("next-photo")
    }

    @Test
    fun `기존 outbox 재시도 횟수와 생성 시각을 보존한다`() {
        val pending = PhotoDeletionOutbox("owned-photo", attempts = 10)
        whenever(outbox.findById("owned-photo")).thenReturn(Optional.of(pending))

        service.deleteUser("deleted-user")
        TransactionSynchronizationManager.getSynchronizations().forEach { it.afterCommit() }

        verify(outbox, never()).save(any<PhotoDeletionOutbox>())
        verify(photos, never()).delete(any())
    }

    private class FakeUsers :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id

        override fun acquireBootstrapLock(key: String): Int = 1
    }
}
