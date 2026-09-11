package com.terraworld.api.user

import com.terraworld.api.upload.PhotoDeletionOutboxDrainer
import com.terraworld.api.upload.R2PhotoStorage
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
import com.terraworld.domain.record.HabitPairRequestKind
import com.terraworld.domain.record.HabitPairRequestRepository
import com.terraworld.domain.record.HabitPairRequestStatus
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import com.terraworld.domain.upload.PhotoDeletionOutbox
import com.terraworld.domain.upload.PhotoDeletionOutboxRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.userdevice.UserDeviceRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.LocalDateTime

/** 공유 데이터를 보존하고 개인정보는 명시 삭제와 DB cascade로 정리한다. */
@Service
@Transactional
class UserDeletionService(
    private val userRepository: UserRepository,
    private val recordRepository: RecordRepository,
    private val deviceRepository: UserDeviceRepository,
    private val trackerRepository: HabitTrackerRepository,
    private val nonceRepository: AdRewardNonceInboxRepository,
    private val exchangeRepository: ExchangeDailyUsageRepository,
    private val photoStorage: R2PhotoStorage,
    private val categoryRepository: CategoryRepository,
    private val pairRequestRepository: HabitPairRequestRepository,
    private val photoOutboxRepository: PhotoDeletionOutboxRepository,
    private val photoOutboxDrainer: PhotoDeletionOutboxDrainer,
) {
    fun deleteUser(userId: String) {
        // 동일 사용자의 bootstrap 및 삭제 재시도를 트랜잭션 단위로 직렬화한다.
        userRepository.acquireBootstrapLock("bootstrap|$userId")
        // 이미 삭제된 사용자의 재시도는 DB 변경과 R2 정리 없이 성공한다.
        if (!userRepository.existsById(userId)) return
        val candidates = recordRepository.findPhotoUrlsByUserId(userId).filter(photoStorage::ownsPublicUrl).distinct()
        val sharedPhotos =
            if (candidates.isEmpty()) emptySet() else recordRepository.findPhotoUrlsReferencedByOtherUsers(userId, candidates).toSet()
        val photos = candidates.filterNot { it in sharedPhotos }
        // soft-delete 기록도 참조를 유지하므로 공유 카테고리는 소유권만 해제한다.
        categoryRepository.releaseSharedCategories(userId)
        settlePendingPairRequests(userId)
        deletePersonalRows(userId)
        // 결제 멱등 원장(entitlement_tx_ledger)과 감사 로그(audit_logs)는 사용자 ID를 포함해 보존한다.
        userRepository.deleteById(userId)
        // users DELETE를 실제 실행한 뒤 재정리한다. 동시 INSERT는 V43 FK가 차단하거나 cascade로 제거한다.
        userRepository.flush()
        deletePersonalRows(userId)

        if (photos.isNotEmpty()) {
            // 사용자 삭제와 같은 트랜잭션에서 보존하며 기존 재시도 횟수는 초기화하지 않는다.
            val pending =
                photos.map { publicUrl ->
                    photoOutboxRepository.findById(publicUrl).orElseGet {
                        photoOutboxRepository.save(PhotoDeletionOutbox(publicUrl))
                    }
                }
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        pending.forEach(photoOutboxDrainer::deletePhoto)
                    }
                },
            )
        }
    }

    private fun deletePersonalRows(userId: String) {
        deviceRepository.deleteAllByUserId(userId)
        trackerRepository.deleteAllByUserId(userId)
        nonceRepository.deleteAllByUserId(userId)
        exchangeRepository.deleteAllByUserId(userId)
    }

    private fun settlePendingPairRequests(userId: String) {
        val now = LocalDateTime.now()
        pairRequestRepository.findOpenByUserId(userId).forEach { request ->
            // HabitService.closeRequest의 거절·만료 규칙: START는 양측 종료, EXTEND는 요청자만 종료한다.
            request.status = if (request.isExpired(now)) HabitPairRequestStatus.EXPIRED else HabitPairRequestStatus.DECLINED
            request.respondedAt = now
            pairRequestRepository.save(request)
            // 기존 중단 전이로 열린 상태만 BROKEN 처리하며, 종료된 상태와 사이클 원장은 보존한다.
            trackerRepository.markBroken(request.requesterTrackerId, request.requesterUserId)
            if (request.kind == HabitPairRequestKind.START) {
                request.partnerTrackerId?.let { trackerRepository.markBroken(it, request.partnerUserId) }
            }
            val peerId = if (request.requesterUserId == userId) request.partnerTrackerId else request.requesterTrackerId
            val peerUserId = if (request.requesterUserId == userId) request.partnerUserId else request.requesterUserId
            if (peerId == null || peerUserId == userId) return@forEach
            trackerRepository.findByIdAndUserId(peerId, peerUserId)?.let { peer ->
                peer.partnerTrackerId = null
                peer.friendLinkId = null
                trackerRepository.save(peer)
            }
        }
    }
}
