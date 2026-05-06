package com.terraworld.common.audit

/**
 * 도메인 서비스가 트랜잭션 안에서 publish 하는 application event.
 *
 * 실제 INSERT 는 [AuditEventListener] 가 트랜잭션 commit 직후 처리.
 *
 * Action 컨벤션 (UPPER_SNAKE, RESOURCE_VERB).
 * 현재 발행되는 actions:
 *   - `REWARD_AD_CLAIMED` — RewardService.claimAdReward
 *   - `EXCHANGE_S2B` — ExchangeService.specialToBasic
 *   - `EXCHANGE_TOKEN` — ExchangeService.exchangeTokens
 *   - `PURCHASE_ITEM` — PurchaseService.purchase
 *   - `ATTENDANCE_CHECKIN` — AttendanceService.checkIn
 *   - `DEVICE_REGISTER` — UserDeviceService.upsert (INSERT 한정)
 *   - `DEVICE_TOKEN_REASSIGN` — UserDeviceService.upsert (cross-user 토큰 회수)
 */
data class AuditEvent(
    val userId: String,
    val action: String,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val payload: Map<String, Any?>? = null,
)
