-- 낙서장 P1 경제: 7화폐 directed exchange rate + daily cap
-- 설계 SoT: docs/plans/2026-07-01-economy-7currency-design.md
-- rate = (to per from). grossTo = floor(fromAmount * rate); netTo = floor(grossTo * (1 - fee_bps/10000)).
-- 모든 pair 가 sink 방향 one-way → arbitrage-free.

CREATE TABLE exchange_rates (
    from_code  VARCHAR(16) NOT NULL REFERENCES currencies(code),
    to_code    VARCHAR(16) NOT NULL REFERENCES currencies(code),
    rate       NUMERIC(18, 6) NOT NULL,
    fee_bps    INT         NOT NULL DEFAULT 1000, -- basis points (1000 = 10%)
    daily_cap  BIGINT      NOT NULL,             -- 유저별 일일 from_amount 상한
    PRIMARY KEY (from_code, to_code),
    CHECK (from_code <> to_code),
    CHECK (rate > 0),
    CHECK (fee_bps >= 0 AND fee_bps < 10000),
    CHECK (daily_cap > 0)
);

-- 허용 pair 7종 (설계 §1)
INSERT INTO exchange_rates (from_code, to_code, rate, fee_bps, daily_cap) VALUES
    ('DEW',  'COIN',    0.100000, 1000, 500),   -- 10 DEW = 1 COIN
    ('SUN',  'COIN',    0.100000, 1000, 500),   -- 10 SUN = 1 COIN
    ('BOLT', 'COIN',    0.100000, 1000, 500),   -- 10 BOLT = 1 COIN
    ('WIND', 'COIN',    0.100000, 1000, 500),   -- 10 WIND = 1 COIN
    ('RUBY', 'COIN',    50.000000,   0, 100),   -- 1 RUBY = 50 COIN (fee 0)
    ('RUBY', 'SPARKLE', 20.000000,   0, 100),   -- 1 RUBY = 20 SPARKLE (fee 0)
    ('COIN', 'SPARKLE', 0.040000, 1000, 300);   -- 25 COIN = 1 SPARKLE

-- 유저별 일일 사용량 (cap 추적). 서비스 로컬 day 경계로 usage_date 저장.
CREATE TABLE exchange_daily_usage (
    user_id     VARCHAR(64) NOT NULL,
    from_code   VARCHAR(16) NOT NULL,
    to_code     VARCHAR(16) NOT NULL,
    usage_date  DATE        NOT NULL,
    from_amount BIGINT      NOT NULL DEFAULT 0,
    version     BIGINT      NOT NULL DEFAULT 0, -- 리뷰 S#1: 낙관락(cap TOCTOU 방어)
    PRIMARY KEY (user_id, from_code, to_code, usage_date)
);

CREATE INDEX idx_exchange_usage_user_date ON exchange_daily_usage (user_id, usage_date);
