-- V40 (아프젝 v2 core 후속 보정, 2026-08-23): 티어별 배경 / 기존 습관 페어·사이클 백필 / 키우기 완료 백필.
--
-- V39 는 스키마·시드만 넣고 기존 데이터의 v2 모델 정합(페어 연결, 사이클 행, 완료 판정)은 비워 두었다.
-- V39 는 일회용 dev DB 에만 적용됐으나 "적용된 버전은 고치지 않는다" 원칙으로 별도 V40 에 둔다. 전부 additive + 멱등.
-- 계약 SoT: docs/root/plans/2026-08-23-apjek-v2-api-contract.md rev2 §R1~§R3.

-- ========================================
-- 1) 테라리움 — 티어(병)별 배경
-- ========================================
-- 배경도 배치처럼 표시 중 병(active_tier) 스코프다. terrariums.background_id 는 유지하되 의미 = "행이 없는 병의 폴백(기본 배경)".
-- 기존 사용자의 현재 배경은 현재 표시 중인 병의 행으로 옮긴다.
CREATE TABLE IF NOT EXISTS terrarium_tier_backgrounds (
    id            BIGSERIAL   PRIMARY KEY,
    terrarium_id  BIGINT      NOT NULL REFERENCES terrariums(id) ON DELETE CASCADE,
    tier          VARCHAR(32) NOT NULL REFERENCES tier_configs(tier),
    background_id BIGINT      NOT NULL REFERENCES terrarium_backgrounds(id),
    updated_at    TIMESTAMP   NOT NULL DEFAULT NOW(),
    CONSTRAINT ux_terrarium_tier_backgrounds UNIQUE (terrarium_id, tier)
);

INSERT INTO terrarium_tier_backgrounds (terrarium_id, tier, background_id)
SELECT t.id, t.active_tier, t.background_id
FROM terrariums t
ON CONFLICT (terrarium_id, tier) DO NOTHING;

COMMENT ON TABLE terrarium_tier_backgrounds IS
    'V40: 병(티어)별 배경. 조회/변경은 terrariums.active_tier 행 — 행이 없으면 terrariums.background_id 폴백';
COMMENT ON COLUMN terrariums.background_id IS
    'V40 이후: 티어 행(terrarium_tier_backgrounds)이 없는 병의 폴백(기본 배경). setBackground 는 이 컬럼을 갱신하지 않는다';

-- ========================================
-- 2) 습관 — 기존 친구 습관 페어 연결 + 사이클 행 백필
-- ========================================
-- V39 이전 친구 습관은 "양쪽이 각자 같은 friend_link_id(수락된 invite.id)로 트래커를 만든 것" 이라 미러 연결이 없다.
-- 같은 link 를 공유하는 서로 다른 두 사용자의 트래커를 partner_tracker_id 로 상호 연결한다.
--   - 대상: status ACTIVE/BROKEN, partner 미연결. 사용자당 1개 선택(ACTIVE 우선, 그다음 최신 id).
--   - COMPLETED 트래커는 건드리지 않는다. 한쪽만 있는 link(상대가 참여 안 함)는 solo 로 남긴다.
--   - 페어 요청(habit_pair_requests) 행은 만들지 않는다 — 열린 START 요청이 없고 상대가 BROKEN 이 아니면
--     partnerStatus 파생 뷰가 ACCEPTED 를, 상대가 BROKEN 이면 PARTNER_STOPPED 를 돌려준다(V39 HabitService.resolveView).
WITH cand AS (
    SELECT DISTINCT ON (friend_link_id, user_id) id, friend_link_id, user_id
    FROM habit_trackers
    WHERE friend_link_id IS NOT NULL
      AND partner_tracker_id IS NULL
      AND status IN ('ACTIVE', 'BROKEN')
    ORDER BY friend_link_id, user_id, (status = 'ACTIVE') DESC, id DESC
),
links AS (
    SELECT friend_link_id
    FROM cand
    GROUP BY friend_link_id
    HAVING COUNT(DISTINCT user_id) = 2
),
pairs AS (
    SELECT a.id AS tracker_id, b.id AS partner_id
    FROM cand a
    JOIN cand b ON b.friend_link_id = a.friend_link_id AND b.user_id <> a.user_id
    JOIN links l ON l.friend_link_id = a.friend_link_id
)
UPDATE habit_trackers t
SET partner_tracker_id = p.partner_id
FROM pairs p
WHERE t.id = p.tracker_id;

-- 사이클 행: 진행 중(ACTIVE) 트래커에 현재 사이클이 없으면 1행 만들고 current_cycle_id 를 건다 (solo 포함 —
-- complete/extend 의 원장 키 habit:{cycleId}:reward:{userId} 가 이 행을 필요로 한다).
--   - cycle_no = completed_cycles + 1, started_on = start_date + 완료 사이클 수 × 사이클 길이(오늘 KST 를 넘지 않게).
--   - reward_sparkle = 완주 시 지급 예정액 — 상대가 연결돼 있고 종료(BROKEN)가 아니면 pair 200, 아니면 solo 100
--     (HabitProperties 기본값과 동일; 실제 지급액은 claim 시점에 다시 계산해 덮어쓴다).
WITH ins AS (
    INSERT INTO habit_cycles (tracker_id, user_id, cycle_no, started_on, reward_sparkle)
    SELECT t.id,
           t.user_id,
           t.completed_cycles + 1,
           LEAST(t.start_date + (t.completed_cycles * t.cycle_length_days), (NOW() AT TIME ZONE 'Asia/Seoul')::date),
           CASE WHEN p.id IS NOT NULL AND p.status <> 'BROKEN' THEN 200 ELSE 100 END
    FROM habit_trackers t
    LEFT JOIN habit_trackers p ON p.id = t.partner_tracker_id
    WHERE t.status = 'ACTIVE'
      AND t.current_cycle_id IS NULL
    RETURNING id, tracker_id
)
UPDATE habit_trackers t
SET current_cycle_id = ins.id
FROM ins
WHERE t.id = ins.tracker_id;

-- ========================================
-- 3) 키우기 — goal(30) 이미 도달한 진행 중 개체를 완료 사이클로 백필
-- ========================================
-- V39 이전 모델에는 완료 상태가 없어 진행 30 이상인 ACTIVE 행이 남아 있다. v2 모델에서는 goal 도달 = COMPLETED
-- (당일 유지 → 다음날 리셋) 이므로 오늘(KST) 완료로 맞춘다. goal 은 BE 설정 growth.goal 의 기본값 30 과 동일.
-- 완료 보상인 정령 아이템(items `{species}-spirit`)도 서비스 경로(GrantService ITEM, 키 growth:{cycle_id}:spirit)와
-- 같은 원장·소유 행을 넣는다 — SQL 만 백필하고 지급을 빠뜨리면 이 사용자들은 다음날 리셋으로 정령을 영영 못 받는다.
WITH done AS (
    UPDATE growth_instances g
    SET cycle_state        = 'COMPLETED',
        completed_kst_date = (NOW() AT TIME ZONE 'Asia/Seoul')::date,
        completed_at       = NOW() AT TIME ZONE 'Asia/Seoul',
        reset_due_kst_date = (NOW() AT TIME ZONE 'Asia/Seoul')::date + 1,
        reset_processed_at = NULL,
        version            = g.version + 1
    WHERE g.cycle_state = 'ACTIVE'
      AND g.natural_streak + g.sparkle_bought_count >= 30
    RETURNING g.id, g.user_id, g.species_code, g.cycle_id
),
spirit AS (
    SELECT d.user_id, d.cycle_id, i.id AS item_id, i.slug
    FROM done d
    JOIN growth_species s ON s.code = d.species_code AND s.kind = 'SPIRIT'
    JOIN items i ON i.slug = s.code || '-spirit'
),
ledger AS (
    INSERT INTO user_grants (user_id, grant_type, grant_ref, amount, idempotency_key, source)
    SELECT user_id, 'ITEM', slug, 1, 'growth:' || cycle_id::text || ':spirit', 'GROWTH_COMPLETE'
    FROM spirit
    ON CONFLICT (user_id, idempotency_key) DO NOTHING
    RETURNING user_id, grant_ref
)
INSERT INTO user_items (user_id, item_id)
SELECT l.user_id, i.id
FROM ledger l
JOIN items i ON i.slug = l.grant_ref
ON CONFLICT (user_id, item_id) DO NOTHING;
