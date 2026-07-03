-- 낙서장 P1: 기록 습관/일상 분리 + 7일 습관 트래커. additive substrate.
-- 설계 SoT: docs/plans/2026-07-01-tier-and-record-split-design.md
--   습관(HABIT): 7일 트래커, 보상 = 반짝이. 일상(DAILY): PHOTO/DIARY/FOCUS/DISTANCE, 보상 = 토큰+코인.

-- activity_records: kind(HABIT/DAILY) + dailyType(PHOTO/DIARY/FOCUS/DISTANCE) + 트래커 연결 + 보상지급 여부
ALTER TABLE activity_records ADD COLUMN kind VARCHAR(16) NOT NULL DEFAULT 'DAILY';
ALTER TABLE activity_records ADD COLUMN daily_type VARCHAR(16);
ALTER TABLE activity_records ADD COLUMN habit_tracker_id BIGINT;
ALTER TABLE activity_records ADD COLUMN reward_granted BOOLEAN NOT NULL DEFAULT TRUE;

-- 7일 습관 트래커 (연속 기록 → 7일 완료 시 반짝이). 연장(7/14/21)은 completed_cycles=1/2/3.
CREATE TABLE habit_trackers (
    id                 BIGSERIAL PRIMARY KEY,
    user_id            VARCHAR(64) NOT NULL,
    title              VARCHAR(100) NOT NULL,
    start_date         DATE NOT NULL,
    current_streak_days INT NOT NULL DEFAULT 0,
    cycle_length_days  INT NOT NULL DEFAULT 7,
    completed_cycles   INT NOT NULL DEFAULT 0,
    status             VARCHAR(16) NOT NULL DEFAULT 'ACTIVE', -- ACTIVE | COMPLETED | BROKEN
    last_checked_date  DATE,
    friend_link_id     BIGINT, -- 친구 연동 (7일 완료 시 양측 2배)
    created_at         TIMESTAMP NOT NULL DEFAULT now(),
    CHECK (cycle_length_days > 0),
    CHECK (current_streak_days >= 0),
    CHECK (completed_cycles >= 0)
);

CREATE INDEX idx_habit_trackers_user ON habit_trackers (user_id, status);
