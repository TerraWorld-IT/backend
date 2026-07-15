-- V36 (Codex 리뷰 백로그, 2026-07-15): revoke 후 재전송 멱등성 — append-only 처리 원장.
--
-- 배경: tx_ref(purchaseToken) 유일성이 현재 상태 테이블(user_entitlement)의 row 에만 존재
-- (uq_user_entitlement_tx_ref partial unique). revoke 가 그 row 를 삭제한 뒤 이전 grant
-- webhook 이 재전송되면(특히 notificationId 없는 경로 — inbox dedup 미적용) 같은 tx_ref 로
-- 다시 insert 되어 취소·환불된 권리가 복원된다. 현재 상태 테이블은 영구 멱등 기록이 아니다.
--
-- 본 원장: tx_ref 별 처리 사실(action)을 append-only 로 영구 기록. UNIQUE (tx_ref, action)
-- 으로 한 purchaseToken 은 GRANT 1회 + REVOKE 1회만 처리 가능 — row 존재 여부와 무관.
--
-- FK 미선언 (audit_logs V9 선례): 원장은 사용자 삭제 후에도 살아남아야 한다 —
-- users FK ON DELETE CASCADE 를 걸면 탈퇴 시 멱등 기록이 함께 소멸해, "row 삭제가
-- dedup 기록을 지운다"는 본 마이그레이션이 고치려는 결함과 같은 부류가 재발한다.

CREATE TABLE IF NOT EXISTS entitlement_tx_ledger (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           VARCHAR(255) NOT NULL,
    entitlement_key   VARCHAR(64)  NOT NULL,
    tx_ref            VARCHAR(128) NOT NULL,
    action            VARCHAR(16)  NOT NULL CHECK (action IN ('GRANT', 'REVOKE')),
    processed_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_entitlement_tx_ledger_ref_action UNIQUE (tx_ref, action)
);

CREATE INDEX IF NOT EXISTS idx_entitlement_tx_ledger_user ON entitlement_tx_ledger(user_id);

COMMENT ON TABLE entitlement_tx_ledger IS
    'V36: tx_ref(purchaseToken) 처리 원장 (append-only). revoke 로 user_entitlement row 가 삭제돼도 grant 재전송이 권리를 복원하지 못하게 tx_ref 당 GRANT 를 1회로 제한';
COMMENT ON COLUMN entitlement_tx_ledger.tx_ref IS
    'Play purchaseToken or Apple transactionId — 전역 멱등 키';
COMMENT ON COLUMN entitlement_tx_ledger.action IS
    'GRANT / REVOKE — UNIQUE (tx_ref, action) 으로 각 1회만 기록';

-- ========================================
-- backfill: 기존 entitlement_event 이력에서 tx_ref 처리 사실 이관
-- ========================================
-- V36 이전에 처리된 grant 의 tx_ref 도 보호해야 한다 — backfill 없이는 과거
-- grant→revoke 된 토큰의 재전송이 여전히 권리를 복원할 수 있다(원장에 기록이 없으므로).
-- entitlement_event 는 V13 부터 append-only 이력이라 자연스러운 원천.
-- 동일 (tx_ref, action) 이 이력에 복수 존재할 수 있어(예: revoke 후 재-grant 가 이미
-- 발생했던 데이터) 최초 발생분만 채택 (DISTINCT ON + occurred_at 오름차순).

-- GRANT backfill: GRANT 이벤트는 과거에도 row insert 성공 시에만 기록됐으므로 그대로 이관.
INSERT INTO entitlement_tx_ledger (user_id, entitlement_key, tx_ref, action, processed_at)
SELECT DISTINCT ON (e.tx_ref)
    e.user_id,
    e.entitlement_key,
    e.tx_ref,
    e.action,
    e.occurred_at
FROM entitlement_event e
WHERE e.tx_ref IS NOT NULL AND e.action = 'GRANT'
ORDER BY e.tx_ref, e.occurred_at ASC
ON CONFLICT (tx_ref, action) DO NOTHING;

-- REVOKE backfill: terminal 승격 — 단, 그 tx_ref 가 **현재 live row** 로 존재하면 제외.
-- V36 이전 결함으로 revoke 후 같은 토큰의 grant 재전송이 이미 성공해 권리가 살아있는 경우,
-- 이를 terminal REVOKED 로 승격하면 그 살아있는 권리의 향후 환불(revoke)이 "중복 REVOKE"
-- 로 오판되어 영구 회수 불능이 된다. live 토큰의 grant 재전송은 어차피 현재 상태 row 의
-- tx_ref 일치 분기(ALREADY_PRESENT)가 막으므로 terminal 승격 없이도 안전하다.
INSERT INTO entitlement_tx_ledger (user_id, entitlement_key, tx_ref, action, processed_at)
SELECT DISTINCT ON (e.tx_ref)
    e.user_id,
    e.entitlement_key,
    e.tx_ref,
    e.action,
    e.occurred_at
FROM entitlement_event e
WHERE e.tx_ref IS NOT NULL
  AND e.action = 'REVOKE'
  AND NOT EXISTS (SELECT 1 FROM user_entitlement ue WHERE ue.tx_ref = e.tx_ref)
ORDER BY e.tx_ref, e.occurred_at ASC
ON CONFLICT (tx_ref, action) DO NOTHING;
