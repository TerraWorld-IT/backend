-- V39 (아프젝 v2 core, 2026-08-23): 티어별 배치 / 습관 상태머신 / 키우기 사이클 / 출석·초대·정령 substrate.
--
-- 계약 SoT: docs/root/plans/2026-08-23-apjek-v2-api-contract.md (rev2 가 §1~§9 보다 우선).
-- 전부 additive + 인플레이스 확장 — 기존 컬럼은 제거하지 않고 의미만 재정의한다
-- (terrariums.tier = 해금 최고 티어, 신규 active_tier = 표시 중 병).

-- ========================================
-- 1) 테라리움 — 표시 중 병(active_tier) + 티어별 배치 스코프
-- ========================================
-- terrariums.tier 는 호환 유지(의미 = highest_unlocked_tier). active_tier 는 현재 표시 중인 병 — 기존 행은 tier 로 백필.
ALTER TABLE terrariums
    ADD COLUMN IF NOT EXISTS active_tier VARCHAR(32) NOT NULL DEFAULT 'GLASS_JAR' REFERENCES tier_configs(tier);
UPDATE terrariums SET active_tier = tier;

COMMENT ON COLUMN terrariums.tier IS
    'V39: 해금 최고 티어(highestUnlockedTier). 해금(unlockTier)만 올리고, 표시 중 병은 active_tier 가 담당';
COMMENT ON COLUMN terrariums.active_tier IS
    'V39: 표시 중인 병(activeTier). PUT /terrarium/active-tier 로 변경 — 배치/자유배치/배경 조회·수정은 이 티어 스코프';

-- 배치는 병(티어)마다 따로 저장 — terrarium_items.tier 추가, 기존 행은 현재 tier 에 귀속.
-- 자유배치 좌표(free_x_pixel/free_y_pixel/is_free_placement)도 같은 테이블이라 함께 스코프된다.
ALTER TABLE terrarium_items
    ADD COLUMN IF NOT EXISTS tier VARCHAR(32) NOT NULL DEFAULT 'GLASS_JAR';
UPDATE terrarium_items ti
SET tier = t.tier
FROM terrariums t
WHERE ti.terrarium_id = t.id;

-- V15 의 (terrarium_id, slot_id) partial UNIQUE 를 (terrarium_id, tier, slot_id) 로 대체 — 같은 슬롯을 병마다 쓸 수 있다.
DROP INDEX IF EXISTS ux_terrarium_items_terrarium_slot;
CREATE UNIQUE INDEX IF NOT EXISTS ux_terrarium_items_terrarium_tier_slot
    ON terrarium_items (terrarium_id, tier, slot_id)
    WHERE slot_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_terrarium_items_terrarium_tier ON terrarium_items (terrarium_id, tier);

COMMENT ON COLUMN terrarium_items.tier IS
    'V39: 이 배치가 속한 병(티어). 조회/수정은 terrariums.active_tier 와 일치하는 행만 대상';

-- 티어 카탈로그 재시드 — 3레벨 루비 전용. 컬럼은 유지하고 level/description_ko/is_active/preview_asset_url 추가.
ALTER TABLE tier_configs
    ADD COLUMN IF NOT EXISTS level INT NOT NULL DEFAULT 1,
    ADD COLUMN IF NOT EXISTS description_ko VARCHAR(100) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS is_active BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS preview_asset_url VARCHAR(500);

UPDATE tier_configs SET level = tier_order;
UPDATE tier_configs SET sparkle_cost = 0, ruby_cost = 0,  slots = 10, spirit_code = NULL,
                        description_ko = '언덕과 바위가 있는 오픈형 테라리움 입니다', is_active = TRUE
WHERE tier = 'GLASS_JAR';
UPDATE tier_configs SET sparkle_cost = 0, ruby_cost = 30, slots = 20, spirit_code = NULL,
                        description_ko = '더 넓은 병에 식물과 장식을 담을 수 있어요', is_active = TRUE
WHERE tier = 'LARGE_JAR';
UPDATE tier_configs SET sparkle_cost = 0, ruby_cost = 50, slots = 40, spirit_code = 'pigeon',
                        description_ko = '정령이 함께 사는 가장 큰 테라리움이에요', is_active = TRUE
WHERE tier = 'GRAND_TANK';
-- HOUSE_TANK 는 비활성(카탈로그 제외, 해금 불가). 이미 도달한 사용자의 terrariums.tier 값은 FK 유지를 위해 행을 남긴다.
UPDATE tier_configs SET is_active = FALSE WHERE tier = 'HOUSE_TANK';

COMMENT ON COLUMN tier_configs.level IS 'V39: 표시용 레벨(= tier_order)';
COMMENT ON COLUMN tier_configs.is_active IS 'V39: FALSE 면 카탈로그에서 제외되고 해금/표시 지정 불가';

-- ========================================
-- 2) 습관 — 상태머신 확장 + 사이클/페어 요청 원장
-- ========================================
-- status 집합 확장: PENDING | ACTIVE | COMPLETED_UNCLAIMED | COMPLETED | BROKEN (COMPLETED_UNCLAIMED 가 16자 초과 → 길이 확장)
ALTER TABLE habit_trackers ALTER COLUMN status TYPE VARCHAR(32);
ALTER TABLE habit_trackers DROP CONSTRAINT IF EXISTS chk_habit_trackers_status;
ALTER TABLE habit_trackers
    ADD CONSTRAINT chk_habit_trackers_status
        CHECK (status IN ('PENDING', 'ACTIVE', 'COMPLETED_UNCLAIMED', 'COMPLETED', 'BROKEN'));

-- 상대 트래커 연결(페어 미러) + 현재 사이클 + 7일 완주 시각
ALTER TABLE habit_trackers
    ADD COLUMN IF NOT EXISTS partner_tracker_id BIGINT,
    ADD COLUMN IF NOT EXISTS current_cycle_id   BIGINT,
    ADD COLUMN IF NOT EXISTS cycle_completed_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_habit_trackers_partner ON habit_trackers (partner_tracker_id);

-- 사이클 원장: 트래커당 7일 사이클 1행. reward_claimed_at 은 complete/extend 의 지급 시각(멱등 게이트 보조).
CREATE TABLE IF NOT EXISTS habit_cycles (
    id                BIGSERIAL   PRIMARY KEY,
    tracker_id        BIGINT      NOT NULL REFERENCES habit_trackers(id) ON DELETE CASCADE,
    user_id           TEXT        NOT NULL,
    cycle_no          INT         NOT NULL,
    started_on        DATE        NOT NULL,
    completed_at      TIMESTAMP,
    reward_claimed_at TIMESTAMP,
    reward_sparkle    BIGINT      NOT NULL DEFAULT 0,
    created_at        TIMESTAMP   NOT NULL DEFAULT NOW(),
    CHECK (cycle_no > 0),
    CHECK (reward_sparkle >= 0)
);
CREATE INDEX IF NOT EXISTS idx_habit_cycles_tracker ON habit_cycles (tracker_id, cycle_no);

-- 페어 요청: 시작(START)/연장(EXTEND) 요청의 상태. 7일 후 만료(expires_at).
CREATE TABLE IF NOT EXISTS habit_pair_requests (
    id                   BIGSERIAL   PRIMARY KEY,
    requester_tracker_id BIGINT      NOT NULL REFERENCES habit_trackers(id) ON DELETE CASCADE,
    requester_user_id    TEXT        NOT NULL,
    partner_tracker_id   BIGINT      REFERENCES habit_trackers(id) ON DELETE CASCADE,
    partner_user_id      TEXT        NOT NULL,
    kind                 VARCHAR(8)  NOT NULL CHECK (kind IN ('START', 'EXTEND')),
    status               VARCHAR(16) NOT NULL CHECK (status IN ('REQUESTED', 'ACCEPTED', 'DECLINED', 'CANCELLED', 'EXPIRED')),
    created_at           TIMESTAMP   NOT NULL DEFAULT NOW(),
    expires_at           TIMESTAMP   NOT NULL,
    responded_at         TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_habit_pair_requests_requester ON habit_pair_requests (requester_tracker_id);
CREATE INDEX IF NOT EXISTS idx_habit_pair_requests_partner   ON habit_pair_requests (partner_tracker_id);

COMMENT ON TABLE habit_cycles IS
    'V39: 습관 7일 사이클 원장. 보상 지급 키 habit:{cycleId}:reward:{userId} 는 user_grants.idempotency_key 로 유니크';
COMMENT ON TABLE habit_pair_requests IS
    'V39: 친구 습관 시작/연장 요청. 수락 전 양측 트래커 PENDING, 거절/취소/만료 시 BROKEN 전환';

-- ========================================
-- 3) 키우기 — 사이클 모델 (LOST / COMPLETED / 리셋 / 되살리기)
-- ========================================
ALTER TABLE growth_instances
    ADD COLUMN IF NOT EXISTS cycle_id                      UUID        NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN IF NOT EXISTS cycle_state                   VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS completed_kst_date            DATE,
    ADD COLUMN IF NOT EXISTS completed_at                  TIMESTAMP,
    ADD COLUMN IF NOT EXISTS reset_due_kst_date            DATE,
    ADD COLUMN IF NOT EXISTS reset_processed_at            TIMESTAMP,
    ADD COLUMN IF NOT EXISTS notify_next                   BOOLEAN     NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS revive_snoozed_until_kst_date DATE,
    ADD COLUMN IF NOT EXISTS lost_at                       TIMESTAMP;
ALTER TABLE growth_instances DROP CONSTRAINT IF EXISTS chk_growth_instances_cycle_state;
ALTER TABLE growth_instances
    ADD CONSTRAINT chk_growth_instances_cycle_state CHECK (cycle_state IN ('ACTIVE', 'LOST', 'COMPLETED'));

-- 스케줄러(KST 00:05) 스캔 경로: 리셋 기한 도래 / 되살리기 보류 만료
CREATE INDEX IF NOT EXISTS idx_growth_instances_reset_due
    ON growth_instances (reset_due_kst_date) WHERE reset_due_kst_date IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_growth_instances_snoozed
    ON growth_instances (revive_snoozed_until_kst_date) WHERE revive_snoozed_until_kst_date IS NOT NULL;

COMMENT ON COLUMN growth_instances.cycle_state IS
    'V39: ACTIVE(진행) | LOST(기록 끊김 — revive/revive-dismiss) | COMPLETED(goal 달성, 다음날 리셋)';
COMMENT ON COLUMN growth_instances.reset_processed_at IS
    'V39: 완료 사이클 리셋 처리 시각 — 스케줄러/조회 이중 경로의 CAS 게이트(IS NULL 일 때만 1회 전환)';

-- 단계 시드 재정의: threshold 1/10/20, goal(30) 은 BE 설정(growth.goal). 라벨은 아프젝 v2 카피.
UPDATE growth_stages SET threshold = 1,  label_ko = '수수께끼 정령'     WHERE kind = 'SPIRIT' AND stage_order = 1;
UPDATE growth_stages SET threshold = 10, label_ko = '고양이 정령 1단계' WHERE kind = 'SPIRIT' AND stage_order = 2;
UPDATE growth_stages SET threshold = 20, label_ko = '고양이 정령 2단계' WHERE kind = 'SPIRIT' AND stage_order = 3;
UPDATE growth_stages SET threshold = 1  WHERE kind = 'PLANT' AND stage_order = 1;
UPDATE growth_stages SET threshold = 10 WHERE kind = 'PLANT' AND stage_order = 2;
UPDATE growth_stages SET threshold = 20 WHERE kind = 'PLANT' AND stage_order = 3;

-- 광고 nonce inbox — 용도 바인딩(AD_REWARD | GROWTH_REVIVE). nonce 는 PK 라 용도 간 재사용도 차단된다.
ALTER TABLE ad_reward_nonce_inbox
    ADD COLUMN IF NOT EXISTS purpose VARCHAR(32) NOT NULL DEFAULT 'AD_REWARD';

-- ========================================
-- 4) 상점 — purchasable + 정령/배경 아이템 시드
-- ========================================
ALTER TABLE items ADD COLUMN IF NOT EXISTS purchasable BOOLEAN NOT NULL DEFAULT TRUE;
COMMENT ON COLUMN items.purchasable IS
    'V39: FALSE 면 상점 비판매(정령 등 지급 전용) — 목록에는 노출(isActive 기준), 구매 시 409';

-- 정령 아이템: items 가 정령 소유권 SoT (GrantService ITEM executor). character_defs 코드 ↔ "{code}-spirit" slug.
INSERT INTO items (slug, name, description, price_type, price_amount, rarity, asset_url, layout, is_animated, is_active, purchasable)
VALUES
    ('cat-spirit',    '고양이 정령', '키우기 30일 완주로 만나는 정령', 'SPECIAL', 0, 'EPIC', '🐱', 'FIGURE', FALSE, TRUE, FALSE),
    ('pigeon-spirit', '비둘기 정령', '대형 테라리움 해금 선물 정령',     'SPECIAL', 0, 'EPIC', '🐦', 'FIGURE', FALSE, TRUE, FALSE),
    ('fish-spirit',   '물고기 정령', '수중 테라리움의 정령',             'SPECIAL', 0, 'EPIC', '🐟', 'FIGURE', FALSE, TRUE, FALSE)
ON CONFLICT (slug) DO NOTHING;

-- 배경 아이템 3종 이상 (코인/토큰 가격, 에셋 플레이스홀더). category 는 이름 JOIN — 토큰 가격은 원소 토큰 카테고리 필수.
INSERT INTO items (slug, name, description, category_id, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated, is_active, purchasable)
SELECT v.slug, v.name, v.description, c.id, v.price_type, v.price_amount, v.token_price, v.rarity, v.asset_url, v.layout, v.is_animated, TRUE, TRUE
FROM (
    VALUES
        ('bg-meadow',   '초원 배경',  '햇살이 드는 초원',     '산책', 'BASIC', 120, NULL::INTEGER, 'COMMON', 'https://cdn.terraworld.app/items/bg-meadow.png',   'BACKGROUND', FALSE),
        ('bg-night',    '밤하늘 배경', '별이 가득한 밤',       '독서', 'MIXED', 150, 20,            'RARE',   'https://cdn.terraworld.app/items/bg-night.png',    'BACKGROUND', FALSE),
        ('bg-beach',    '해변 배경',  '파도가 치는 모래사장', '러닝', 'TOKEN', 30,  NULL,          'COMMON', 'https://cdn.terraworld.app/items/bg-beach.png',    'BACKGROUND', FALSE),
        ('bg-studio',   '작업실 배경', '아늑한 낙서 작업실',   '낙서', 'BASIC', 100, NULL,          'COMMON', 'https://cdn.terraworld.app/items/bg-studio.png',   'BACKGROUND', FALSE)
) AS v(slug, name, description, category_name, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated)
JOIN categories c ON c.name = v.category_name AND c.is_custom = FALSE
ON CONFLICT (slug) DO NOTHING;

-- ========================================
-- 5) 알림 — type HABIT 추가
-- ========================================
ALTER TABLE user_notifications DROP CONSTRAINT IF EXISTS user_notifications_type_check;
ALTER TABLE user_notifications
    ADD CONSTRAINT user_notifications_type_check
        CHECK (type IN ('SPIRIT_ARRIVED', 'PAYMENT', 'FRIEND_JOINED', 'CHEER', 'SYSTEM', 'HABIT'));
