-- UltraPlan v3 J-EVO-001 + P-EVOLVE-001 (2026-05-18)
-- Codex post-audit MEDIUM 3 반영: 레벨 상한 10 + decay 없음 + 보상 enum 확장 path
--
-- 변경 사유: Codex pre-audit J-EVO-001 — RecordService.kt:166 에서 totalExp += 10 만
-- 갱신하고 user.level 자동 증가 X. level_config seed 도 없거나 미완 → UserLevelService
-- 가 임계값 검색 불가.
--
-- 권장 default (§4.2.A 자율 진행): 선형 곡선 (level N 누적 EXP = N × 100). 10 단계 seed
-- (5 단계 = 진화 unlock + 6~10 단계 = retention 추가 보상). Decay 없음 (사용자 박탈감
-- 회피, 광고 복구·환불·시들기 모두 EXP 변동 X).
--
-- Phase 5 KPI 측정 후 admin 이 PUT /admin/levels/:id 로 require_exp / reward 조정 가능.

-- 1) level_config seed (idempotent — 기존 seed 있어도 ON CONFLICT 로 skip)
-- ⚠️ V8 migration cross-check (Codex caveat 2026-05-18): V1 + V2 에 level_configs (복수) seed 이미 있음.
-- 본 V12 의 ON CONFLICT DO NOTHING 으로 silent skip — 의도된 idempotency. V8 seed 가 N×100 패턴이 아니면
-- admin 이 별 cycle 에서 PUT /admin/levels/:id 로 조정 권장.
INSERT INTO level_configs (level, required_exp, reward_type, reward_value, max_items) VALUES
    (1, 0,    'NONE',       0,   5),
    (2, 100,  'COIN',       50,  7),
    (3, 200,  'COIN',       100, 9),
    (4, 300,  'SPECIAL',    5,   11),
    (5, 400,  'BACKGROUND', 1,   13),
    -- Phase 5+ retention 보상 (Codex MEDIUM 3 — 레벨 상한 10)
    (6, 500,  'COIN',       200, 13),
    (7, 600,  'SPECIAL',    10,  13),
    (8, 700,  'COIN',       400, 13),
    (9, 800,  'SPECIAL',    15,  13),
    (10, 900, 'BACKGROUND', 1,   15)
ON CONFLICT (level) DO NOTHING;

-- 2) terrarium 에 evolution_unlocked_at 추가 (P-EVOLVE — 자동 진화 unlock 시각 추적)
ALTER TABLE terrariums
    ADD COLUMN evolution_unlocked_at TIMESTAMP NULL;

COMMENT ON COLUMN terrariums.evolution_unlocked_at IS
    'P-EVOLVE-001: level-up 으로 다음 진화 단계 unlock 된 시각. NULL = 아직 unlock 안 됨';
