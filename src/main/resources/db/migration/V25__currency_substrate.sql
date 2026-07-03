-- V25: 화폐 정규화 substrate (낙서장 리팩토링 P0)
-- additive — 구 저장(users.basic_coin/special_coin, user_tokens)은 P1 caller 이관 후 드롭.

-- 화폐 config (7종 고정: 코인/루비/반짝이 + 이슬/햇살/번개/바람)
CREATE TABLE currencies (
    code           VARCHAR(16) PRIMARY KEY,
    kind           VARCHAR(16) NOT NULL,           -- TOKEN | COIN | SPARKLE | RUBY
    label_ko       VARCHAR(32) NOT NULL,
    is_purchasable BOOLEAN     NOT NULL DEFAULT FALSE, -- RUBY 만 true (결제 활성은 트랙R)
    sort_order     INT         NOT NULL
);

INSERT INTO currencies (code, kind, label_ko, is_purchasable, sort_order) VALUES
    ('COIN',    'COIN',    '코인',   FALSE, 1),
    ('RUBY',    'RUBY',    '루비',   TRUE,  2),
    ('SPARKLE', 'SPARKLE', '반짝이', FALSE, 3),
    ('DEW',     'TOKEN',   '이슬',   FALSE, 4),
    ('SUN',     'TOKEN',   '햇살',   FALSE, 5),
    ('BOLT',    'TOKEN',   '번개',   FALSE, 6),
    ('WIND',    'TOKEN',   '바람',   FALSE, 7);

-- 정규화 잔액 (단일축, 낙관적 락) — P1 write-side 컷오버 대상
CREATE TABLE user_currency_balances (
    user_id       TEXT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    currency_code VARCHAR(16) NOT NULL REFERENCES currencies(code),
    amount        BIGINT      NOT NULL DEFAULT 0 CHECK (amount >= 0),
    version       BIGINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (user_id, currency_code)
);

-- 다형 지급 원장 (멱등 — idempotency_key = actor+operation+payload-hash)
CREATE TABLE user_grants (
    id              BIGSERIAL PRIMARY KEY,
    user_id         TEXT         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    grant_type      VARCHAR(16)  NOT NULL,   -- CURRENCY | SPIRIT | ITEM | BACKGROUND
    grant_ref       VARCHAR(64)  NOT NULL,   -- currency code | spirit code | item id | bg code
    amount          BIGINT       NOT NULL DEFAULT 1,
    idempotency_key VARCHAR(160) NOT NULL,
    source          VARCHAR(32)  NOT NULL,   -- STREAK_GROW | TIER_UNLOCK | EVENT | IAP | ...
    created_at      TIMESTAMP    NOT NULL DEFAULT now(),
    -- idempotency 는 (user_id, key) 스코프 — cross-user 억제 방지(security-review #3)
    CONSTRAINT ux_user_grants_idem UNIQUE (user_id, idempotency_key)
);
CREATE INDEX ix_user_grants_user ON user_grants (user_id);

-- 원장(wallet_transactions) typed ref + currency_code (additive; 구 currency_type/reference_id 는 P1 정리)
ALTER TABLE wallet_transactions ADD COLUMN currency_code VARCHAR(16);
ALTER TABLE wallet_transactions ADD COLUMN ref_type      VARCHAR(32);
ALTER TABLE wallet_transactions ADD COLUMN ref_key       VARCHAR(160);
