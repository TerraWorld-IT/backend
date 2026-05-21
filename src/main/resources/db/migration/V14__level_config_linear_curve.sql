-- UltraPlan v3 §4.2.A 권장 default 자율 승인 (사용자 메타 결정 2026-05-18):
-- "EXP 선형 N×100" — level N 의 required_exp = (N-1) × 100.
--
-- 배경 (2026-05-18 /analyze 회귀 검증 결과):
--   V2__seed_data.sql:14-24 가 production seed 로 적용됨 — required_exp 가 triangular 곡선
--   (100×(n-1)×n/2, 만렙 4500 EXP). 의도된 선형 N×100 곡선과 mismatch.
--   V12__level_config_seed_and_evolution_unlock.sql 의 ON CONFLICT (level) DO NOTHING 으로 silent skip.
--
-- 본 V14 의 역할:
--   level_configs 의 required_exp 만 V12 의 선형 N×100 값으로 UPDATE. 즉:
--     level 1 → 0,   level 2 → 100, level 3 → 200, level 4 → 300, level 5 → 400,
--     level 6 → 500, level 7 → 600, level 8 → 700, level 9 → 800, level 10 → 900
--   → 만렙 누적 = 900 EXP (V2 의 4500 EXP 대비 1/5)
--
-- 미터치 항목 (사용자 결정 영역):
--   - reward_type / reward_value: V2 = 'MAX_ITEMS_UP' / 'BACKGROUND_UNLOCK' (현재)
--                                  V12 = 'COIN' / 'SPECIAL' / 'BACKGROUND' (의도)
--     두 enum set 이 다름 — 사용자 결정 (옵션 통합 vs 분리 vs 확장) 후 별 migration 에서 처리.
--   - max_items: V2 = 10~40 (현재), V12 = 5~15 (의도) — reward_type 결정과 함께 처리.
--
-- 영향:
--   - 본 cycle 은 Phase 4 출시 준비 단계 — production 사용자 0건 (공모전 pre-launch).
--   - UserLevelService.kt 가 다음 호출 시 user.totalExp 와 갱신된 required_exp 로 재계산 → 자동 level-up
--     가능 (예: totalExp=1500 사용자가 V2 기준 level 6 → V14 후 level 10).
--   - WalletBar.vue 의 progressPercent 가 본 update 후 정합 (V12 컨벤션 currentLevelExp = (N-1)×100 사용).

UPDATE level_configs SET required_exp = 0    WHERE level = 1;
UPDATE level_configs SET required_exp = 100  WHERE level = 2;
UPDATE level_configs SET required_exp = 200  WHERE level = 3;
UPDATE level_configs SET required_exp = 300  WHERE level = 4;
UPDATE level_configs SET required_exp = 400  WHERE level = 5;
UPDATE level_configs SET required_exp = 500  WHERE level = 6;
UPDATE level_configs SET required_exp = 600  WHERE level = 7;
UPDATE level_configs SET required_exp = 700  WHERE level = 8;
UPDATE level_configs SET required_exp = 800  WHERE level = 9;
UPDATE level_configs SET required_exp = 900  WHERE level = 10;
