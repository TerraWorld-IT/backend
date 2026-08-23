package com.terraworld.api.growth

import com.terraworld.common.time.KstTime
import com.terraworld.domain.growth.GrowthInstanceRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 키우기 사이클 리셋 스케줄러 (아프젝 v2 §R3) — 매일 KST 00:05.
 *
 * 완료(COMPLETED, reset_due 도래) / 되살리기 보류 만료(LOST, revive_snoozed_until 도래) 개체를 새 사이클로 전환한다.
 * 조회 경로([GrowthService.settle])와 같은 CAS 문장을 쓰므로 사용자가 먼저 조회해 전환됐으면 여기서는 rows==0 — 이중 발송 없음.
 * 개체마다 별도 tx([GrowthService.resetDueById]) — 한 건 실패가 전체를 롤백하지 않는다 (WiltScheduler 의 chunk 분할 취지와 동일).
 * SPIRIT_ARRIVED 는 그 tx 안에서 publish 되어 AFTER_COMMIT 리스너가 커밋 후 처리한다.
 */
@Component
class GrowthResetScheduler(
    private val growthInstanceRepository: GrowthInstanceRepository,
    private val growthService: GrowthService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(cron = "0 5 0 * * *", zone = "Asia/Seoul")
    fun runDailyReset() {
        val today = KstTime.today()
        val dueIds =
            (growthInstanceRepository.findCompletedResetDueIds(today) + growthInstanceRepository.findSnoozeExpiredIds(today)).distinct()
        var reset = 0
        var failed = 0
        dueIds.forEach { id ->
            try {
                if (growthService.resetDueById(id, today)) reset++
            } catch (e: Exception) {
                failed++
                log.error("growth.reset.failed id={} — 다음 개체 계속 (조회 시 lazy 전환으로 재처리)", id, e)
            }
        }
        log.info("growth.reset.done date={} candidates={} reset={} failed={}", today, dueIds.size, reset, failed)
    }
}
