-- V26: 캐릭터(정령) substrate (낙서장 리팩토링 P0b) — P2 티어 정령지급·P3 육성 수신처.
-- 정책 #5: 정령 판매 X, 획득경로 = DEFAULT(기본제공) / GROW(키우기 마일스톤) / TIER(테라리움 티어지급) / EVENT.

CREATE TABLE character_defs (
    code           VARCHAR(32) PRIMARY KEY,
    name_ko        VARCHAR(32) NOT NULL,
    habitat        VARCHAR(16) NOT NULL,       -- LAND | AQUATIC (수중=PALUDARIUM+ 조건)
    sellable       BOOLEAN     NOT NULL DEFAULT FALSE,
    acquire_source VARCHAR(16) NOT NULL,       -- DEFAULT | GROW | TIER | EVENT
    sort_order     INT         NOT NULL
);

INSERT INTO character_defs (code, name_ko, habitat, acquire_source, sort_order) VALUES
    ('cat',      '고양이',   'LAND',    'DEFAULT', 1),
    ('axolotl',  '우파루파', 'AQUATIC', 'GROW',    2),
    ('otter',    '수달',     'LAND',    'GROW',    3),
    ('squirrel', '다람쥐',   'LAND',    'GROW',    4),
    ('rabbit',   '토끼',     'LAND',    'GROW',    5),
    ('pigeon',   '비둘기',   'LAND',    'TIER',    6),
    ('fish',     '물고기',   'AQUATIC', 'TIER',    7),
    ('dino',     '공룡',     'LAND',    'EVENT',   8);

CREATE TABLE user_characters (
    id             BIGSERIAL   PRIMARY KEY,
    user_id        TEXT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    character_code VARCHAR(32) NOT NULL REFERENCES character_defs(code),
    acquired_via   VARCHAR(24) NOT NULL,   -- grant source (STREAK_GROW | TIER_UNLOCK | EVENT | ...)
    acquired_at    TIMESTAMP   NOT NULL DEFAULT now(),
    CONSTRAINT ux_user_characters UNIQUE (user_id, character_code)  -- 중복 소유 방지(멱등)
);
CREATE INDEX ix_user_characters_user ON user_characters (user_id);
