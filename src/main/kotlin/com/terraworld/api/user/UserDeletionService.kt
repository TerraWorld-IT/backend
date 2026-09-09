package com.terraworld.api.user

import com.terraworld.api.upload.R2PhotoStorage
import com.terraworld.domain.exchange.ExchangeDailyUsageRepository
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

/** 사용자 FK가 없는 개인정보를 명시적으로 정리하고 나머지는 DB cascade로 삭제한다. */
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun deleteUser(userId: String) {
        // 동일 사용자의 bootstrap 및 삭제 재시도를 트랜잭션 단위로 직렬화한다.
        userRepository.acquireBootstrapLock("bootstrap|$userId")
        val photos = recordRepository.findPhotoUrlsByUserId(userId).filter(photoStorage::ownsPublicUrl).distinct()
        deviceRepository.deleteAllByUserId(userId)
        trackerRepository.deleteAllByUserId(userId)
        nonceRepository.deleteAllByUserId(userId)
        exchangeRepository.deleteAllByUserId(userId)
        // 결제 멱등 원장(entitlement_tx_ledger)과 감사 로그(audit_logs)는 사용자 ID를 포함해 보존한다.
        userRepository.deleteById(userId)

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
}
