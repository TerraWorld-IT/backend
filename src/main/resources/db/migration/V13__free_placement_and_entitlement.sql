-- UltraPlan v3 J-FREE-001 (2026-05-18)
-- 자유배치 entitlement + IAP refund/revoke ledger
--
-- Codex post-audit HIGH 1 + 2 반영:
--   1) Flyway 순번 V13 예약 (backend V10 → V11 wilt → V13 free)
--   2) entitlement_event ledger (refund/revoke 추적, Codex HIGH 1)
--
-- 변경 사유: 자유배치 (5 슬롯 외 자유 위치) PoC 상태 (`usePayment.ts:146`,
-- `TerrariumService.kt:111` 메모리 only). IAP 결제 → entitlement 부여 +
-- 환불 시 entitlement revoke + soft-effect (자유배치 → 슬롯 fallback) 필요.

-- ========================================
-- 1) user_entitlement — 현재 활성 entitlement
-- ========================================
CREATE TABLE IF NOT EXISTS user_entitlement (
    user_id           VARCHAR(255) NOT NULL,
    entitlement_key   VARCHAR(64)  NOT NULL,
    granted_at        TIMESTAMP    NOT NULL DEFAULT NOW(),
    tx_ref            VARCHAR(128) NULL,
    PRIMARY KEY (user_id, entitlement_key),
    CONSTRAINT fk_user_entitlement_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_user_entitlement_key ON user_entitlement(entitlement_key);

-- PAY-004 fix (Codex audit MEDIUM, 2026-05-18):
-- tx_ref (Play purchaseToken) UNIQUE — 동일 purchaseToken 의 동시 webhook 처리 시 race condition 방어.
-- PRIMARY KEY (user_id, entitlement_key) 외에 tx_ref 별 unique 보장 → 중복 grant 차단.
-- NULL tx_ref (SYSTEM_GRANT 등) 는 unique 무관 (PostgreSQL 의 partial index 활용).
CREATE UNIQUE INDEX IF NOT EXISTS uq_user_entitlement_tx_ref
    ON user_entitlement(tx_ref) WHERE tx_ref IS NOT NULL;

COMMENT ON TABLE user_entitlement IS
    'J-FREE-001: IAP / 시스템 entitlement. 환불 시 row 삭제 + entitlement_event ledger 추가';
COMMENT ON COLUMN user_entitlement.entitlement_key IS
    'free_placement / season_pass_2026q4 / ... (key naming = snake_case)';
COMMENT ON COLUMN user_entitlement.tx_ref IS
    'Play purchaseToken or Apple receipt ID — 환불 추적 + 재구매 시 idempotency';

-- ========================================
-- 2) entitlement_event — 영구 ledger (refund/chargeback 추적)
-- ========================================
CREATE TABLE IF NOT EXISTS entitlement_event (
    id                BIGSERIAL    PRIMARY KEY,
    user_id           VARCHAR(255) NOT NULL,
    entitlement_key   VARCHAR(64)  NOT NULL,
    action            VARCHAR(16)  NOT NULL CHECK (action IN ('GRANT', 'REVOKE')),
    reason            VARCHAR(32)  NOT NULL,
    tx_ref            VARCHAR(128) NULL,
    occurred_at       TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_entitlement_event_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_entitlement_event_user_key ON entitlement_event(user_id, entitlement_key);
CREATE INDEX IF NOT EXISTS idx_entitlement_event_occurred ON entitlement_event(occurred_at);

COMMENT ON TABLE entitlement_event IS
    'J-FREE-001: entitlement 부여/회수 영구 ledger. user_entitlement 의 history';
COMMENT ON COLUMN entitlement_event.reason IS
    'PURCHASE / REFUND / CHARGEBACK / ADMIN_OVERRIDE / SYSTEM_GRANT';

-- ========================================
-- 3) terrarium_items 에 free placement 컬럼 추가
-- ========================================
ALTER TABLE terrarium_items
    ADD COLUMN free_x_pixel        INTEGER NULL,
    ADD COLUMN free_y_pixel        INTEGER NULL,
    ADD COLUMN is_free_placement   BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN terrarium_items.free_x_pixel IS
    'J-FREE-001: 자유배치 X 좌표 (픽셀, canvas 기준). is_free_placement=TRUE 시만 사용';
COMMENT ON COLUMN terrarium_items.free_y_pixel IS
    'J-FREE-001: 자유배치 Y 좌표 (픽셀)';
COMMENT ON COLUMN terrarium_items.is_free_placement IS
    'TRUE 시 free_x/y_pixel 사용 (entitlement 필요). FALSE 시 기존 pos_x/y/slot 사용';
