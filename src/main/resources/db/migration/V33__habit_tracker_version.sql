-- 감사 LOW#23: habit_trackers 낙관적 락(@Version) 컬럼.
-- 동시 checkIn 시 same-day read-check(lastCheckedDate==today) 만으로는 cycle 완료 중복 지급(반짝이 2배) race 존재.
-- @Version 으로 경합 시 한쪽만 커밋되고 다른쪽은 rollback(같은 tx 라 SPARKLE credit 도 함께 롤백) → 중복 없음(fail-closed).
ALTER TABLE habit_trackers ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
