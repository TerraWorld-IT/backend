package com.terraworld.common.audit

/**
 * 도메인 서비스가 트랜잭션 안에서 publish 하는 application event.
 *
 * 실제 INSERT 는 [AuditEventListener] 가 트랜잭션 commit 직후 처리.
 *
 * Action 컨벤션 (대문자 + 언더스코어):
 *   `REWARD_AD_CLAIMED`, `EXCHANGE_S2B`, `EXCHANGE_TOKEN`, `PURCHASE_ITEM`,
 *   `ATTENDANCE_CHECKIN`, `INVITE_CREATE`, `INVITE_ACCEPT`, ...
 */
data class AuditEvent(
    val userId: String,
    val action: String,
    val resourceType: String? = null,
    val resourceId: String? = null,
    val payload: Map<String, Any?>? = null,
)
