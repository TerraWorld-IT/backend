-- 낙서장 P1: 레벨/EXP + evolution(레벨-게이트) 완전 제거.
-- 진행은 (a) 연속 기록 스트릭(육성) + (b) 화폐 티어 해금(P2 tier_configs)으로 대체 — 레벨과 무관.
-- 파괴적 변경 (prototype, prod 없음).

-- 레벨 설정 테이블 제거
DROP TABLE IF EXISTS level_configs;

-- users 레벨/EXP 컬럼 제거
ALTER TABLE users DROP COLUMN IF EXISTS level;
ALTER TABLE users DROP COLUMN IF EXISTS total_exp;

-- terrariums evolution 컬럼 제거 (tier 로 대체 — V29)
ALTER TABLE terrariums DROP COLUMN IF EXISTS evolution_stage;
ALTER TABLE terrariums DROP COLUMN IF EXISTS evolution_unlocked_at;
