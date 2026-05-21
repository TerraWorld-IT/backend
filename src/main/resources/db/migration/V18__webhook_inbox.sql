-- N11 (구현 계획서 v4, 2026-05-21): IAP webhook event inbox.
--
-- 변경 사유: PlayBillingWebhookController 가 Google Play RTDN 을 수신하나, 동일 notification
-- 의 중복 수신 (Pub/Sub at-least-once delivery 특성) 에 대한 inbox 패턴 부재. entitlement
-- grant 자체는 idempotent (uq_user_entitlement_tx_ref) 하나, ledger / audit 중복 기록 가능.
--
-- 본 inbox: notification_id (Pub/Sub messageId 등) 를 PK 로 — 이미 처리된 notification 은
-- 재처리 skip. notification_id 미제공 callers 는 inbox 우회 (dedup skip + warn log).

CREATE TABLE IF NOT EXISTS webhook_inbox (
    notification_id    VARCHAR(255) PRIMARY KEY,
    notification_type  VARCHAR(64)  NOT NULL,
    received_at        TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_webhook_inbox_received ON webhook_inbox(received_at);

COMMENT ON TABLE webhook_inbox IS
    'N11: webhook notification 중복 수신 dedup. notification_id 가 이미 있으면 재처리 skip';
