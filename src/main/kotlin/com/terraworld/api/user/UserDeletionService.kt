package com.terraworld.api.user

import com.terraworld.api.upload.R2PhotoStorage
import com.terraworld.domain.category.CategoryRepository
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
import com.terraworld.domain.record.HabitPairRequestKind
import com.terraworld.domain.record.HabitPairRequestRepository
import com.terraworld.domain.record.HabitStatus
import com.terraworld.domain.record.HabitTrackerRepository
import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.reward.AdRewardNonceInboxRepository
import com.terraworld.domain.user.UserRepository
import com.terraworld.domain.userdevice.UserDeviceRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager

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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun deleteUser(userId: String) {
        // 동일 사용자의 bootstrap 및 삭제 재시도를 트랜잭션 단위로 직렬화한다.
        userRepository.acquireBootstrapLock("bootstrap|$userId")
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
            TransactionSynchronizationManager.registerSynchronization(
                object : TransactionSynchronization {
                    override fun afterCommit() {
                        photos.forEach { publicUrl ->
                            try {
                                photoStorage.delete(publicUrl)
                            } catch (ex: Exception) {
                                // URL이나 SDK 예외 본문의 개인정보를 남기지 않고 실패 종류만 기록한다.
                                log.warn("계정 삭제 후 R2 사진 정리 실패: {}", ex.javaClass.simpleName)
                            }
                        }
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
        pairRequestRepository.findOpenByUserId(userId).forEach { request ->
            val peerId = if (request.requesterUserId == userId) request.partnerTrackerId else request.requesterTrackerId
            val peerUserId = if (request.requesterUserId == userId) request.partnerUserId else request.requesterUserId
            if (peerId == null || peerUserId == userId) return@forEach
            // HabitService와 같은 CAS를 재사용한다. START는 종료, EXTEND는 이미 열린 새 사이클을 단독 진행한다.
            val target = if (request.kind == HabitPairRequestKind.START) HabitStatus.BROKEN else HabitStatus.ACTIVE
            trackerRepository.casStatus(peerId, peerUserId, HabitStatus.PENDING, target)
            trackerRepository.findByIdAndUserId(peerId, peerUserId)?.let { peer ->
                peer.partnerTrackerId = null
                peer.friendLinkId = null
                trackerRepository.save(peer)
            }
        }
    }
}
