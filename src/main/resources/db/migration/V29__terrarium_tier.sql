-- 낙서장 P2: 테라리움 티어 (evolution 레벨-게이트 → 화폐-게이트). additive — evolution_stage 는 P1 레벨제거 슬라이스에서 드롭.
-- 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md

-- 티어 카탈로그 (반짝이/루비 해금 비용 + 슬롯 + 티어 보상 정령)
CREATE TABLE tier_configs (
    tier         VARCHAR(32) PRIMARY KEY,
    tier_order   INT         NOT NULL UNIQUE,
    name_ko      VARCHAR(32) NOT NULL,
    sparkle_cost BIGINT      NOT NULL DEFAULT 0,
    ruby_cost    BIGINT      NOT NULL DEFAULT 0,
    slots        INT         NOT NULL,
    spirit_code  VARCHAR(32) REFERENCES character_defs(code), -- 해금 시 지급 정령(없으면 NULL)
    CHECK (sparkle_cost >= 0 AND ruby_cost >= 0),
    CHECK (slots > 0)
);

INSERT INTO tier_configs (tier, tier_order, name_ko, sparkle_cost, ruby_cost, slots, spirit_code) VALUES
    ('GLASS_JAR',  1, '유리병',   0,    0,   6,  NULL),
    ('LARGE_JAR',  2, '큰유리병', 240,  0,   10, NULL),
    ('GRAND_TANK', 3, '대형',     720,  60,  16, 'pigeon'),
    ('HOUSE_TANK', 4, '하우스형', 1600, 180, 24, 'fish');

-- terrariums 에 tier 컬럼 (신 SoT). 신규 유저 = GLASS_JAR(tier 1).
ALTER TABLE terrariums ADD COLUMN tier VARCHAR(32) NOT NULL DEFAULT 'GLASS_JAR' REFERENCES tier_configs(tier);

-- 기존 evolution_stage → tier 매핑 (신 컬럼 채우기; evolution_stage 는 후속 슬라이스에서 드롭)
UPDATE terrariums SET tier = CASE evolution_stage
    WHEN 'POT'        THEN 'GLASS_JAR'
    WHEN 'BOTTLE'     THEN 'LARGE_JAR'
    WHEN 'PALUDARIUM' THEN 'GRAND_TANK'
    WHEN 'WORLD'      THEN 'HOUSE_TANK'
    WHEN 'CUSTOM'     THEN 'HOUSE_TANK'
    ELSE 'GLASS_JAR'
END;
