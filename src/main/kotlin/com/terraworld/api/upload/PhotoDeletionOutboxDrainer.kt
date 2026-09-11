package com.terraworld.api.upload

import com.terraworld.domain.record.RecordRepository
import com.terraworld.domain.upload.PhotoDeletionOutbox
import com.terraworld.domain.upload.PhotoDeletionOutboxRepository
import org.slf4j.LoggerFactory
import org.springframework.data.domain.PageRequest
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.LocalDateTime

/** 부팅 작업과 분리된 스케줄러 스레드에서 한 번에 제한된 사진만 재시도한다. */
@Component
class PhotoDeletionOutboxDrainer(
    private val repository: PhotoDeletionOutboxRepository,
    private val photoStorage: R2PhotoStorage,
    private val recordRepository: RecordRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(
        fixedDelayString = "\${app.photo-outbox.retry-delay-ms:300000}",
        initialDelayString = "\${app.photo-outbox.retry-delay-ms:300000}",
    )
    fun drain() {
        try {
            repository
                .findByAttemptsLessThanOrderByCreatedAtAscPublicUrlAsc(MAX_ATTEMPTS, PageRequest.of(0, BATCH_SIZE))
                .forEach(::deletePhoto)
        } catch (ex: Exception) {
            log.warn("사진 정리 outbox 조회 실패: {}", ex.javaClass.simpleName)
        }
    }

    fun deletePhoto(photo: PhotoDeletionOutbox) {
        if (photo.attempts >= MAX_ATTEMPTS) {
            log.warn("사진 정리 재시도 한도 도달, 수동 처리 필요")
            return
        }
        try {
            // 미설정 또는 URL 설정 변경으로 인한 무동작을 삭제 성공으로 취급하지 않는다.
            check(photoStorage.isEnabled() && photoStorage.ownsPublicUrl(photo.publicUrl))
            // 탈퇴 후 새로 공유된 사진은 보존하고 삭제 대기열에서 제외한다.
            if (recordRepository.existsByPhotoUrl(photo.publicUrl)) {
                repository.deleteCompleted(photo.publicUrl)
                return
            }
            photoStorage.delete(photo.publicUrl)
            repository.deleteCompleted(photo.publicUrl)
        } catch (ex: Exception) {
            // URL과 SDK 예외 본문은 기록하지 않는다. DB 실패도 다음 사진 처리를 막지 않는다.
            log.warn("사진 정리 실패: {}", ex.javaClass.simpleName)
            try {
                repository.recordFailure(photo.publicUrl, LocalDateTime.now(), MAX_ATTEMPTS)
                if (photo.attempts + 1 >= MAX_ATTEMPTS) {
                    log.warn("사진 정리 재시도 한도 도달, 수동 처리 필요")
                }
            } catch (updateEx: Exception) {
                log.warn("사진 정리 실패 기록 갱신 실패: {}", updateEx.javaClass.simpleName)
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 10
        const val BATCH_SIZE = 50
    }
}
