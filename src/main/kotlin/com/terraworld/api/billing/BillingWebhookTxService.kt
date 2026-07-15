package com.terraworld.api.billing

import com.terraworld.domain.billing.WebhookInboxRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

/**
 * BE-01 (2026-07-15 성능 감사): RTDN webhook 의 DB 반영 구간만 묶는 짧은 tx 경계.
 *
 * 기존: PlayBillingWebhookController.handleRtdn/handlePubSubRtdn 이 @Transactional 안에서
 * Google Play verify(외부 HTTP, timeout 8s) 를 호출 — DB 커넥션(pool 10)을 외부 응답 대기
 * 동안 점유하는 pool 고갈 벡터. 변경: verify 는 tx 밖(컨트롤러), DB 반영(inbox dedup +
 * entitlement grant/revoke)만 본 서비스의 @Transactional 로 축소.
 *
 * **불변 제약**: inbox insertIfAbsent 와 grant/revoke 는 반드시 같은 tx 에서 커밋한다.
 * inbox 만 먼저 별도 커밋하면 grant 실패 후 Pub/Sub 재전송이 duplicate 로 판정되어
 * 권리가 영구 유실된다 — 분리 금지. action 실패(예외) 시 tx 전체 rollback → inbox 미기록
 * → 5xx 응답 → Pub/Sub/클라이언트 재전송이 정상 재처리.
 *
 * 별도 @Service 인 이유: @Transactional 은 self-invocation 에 적용되지 않는다 —
 * 컨트롤러 내부 private 메서드로는 tx 경계를 만들 수 없다.
 */
@Service
class BillingWebhookTxService(
    private val webhookInboxRepository: WebhookInboxRepository,
) {
    /**
     * inbox dedup + entitlement 반영을 하나의 짧은 tx 로 실행.
     *
     * @param notificationId dedup 키 (RTDN notificationId / Pub/Sub messageId). null 이면
     *   dedup 우회 — entitlement grant 자체가 멱등(uq_user_entitlement_tx_ref)이라 안전.
     * @param action entitlement grant/revoke — EntitlementService 의 @Transactional(REQUIRED)
     *   메서드가 본 tx 에 참여한다.
     * @return true = 신규 처리됨 / false = duplicate (이미 처리된 notification — skip)
     */
    @Transactional
    fun processWithInboxDedup(
        notificationId: String?,
        notificationType: String,
        action: () -> Unit,
    ): Boolean {
        if (notificationId != null) {
            val inserted = webhookInboxRepository.insertIfAbsent(notificationId, notificationType)
            if (inserted == 0) {
                return false
            }
        }
        action()
        return true
    }
}
