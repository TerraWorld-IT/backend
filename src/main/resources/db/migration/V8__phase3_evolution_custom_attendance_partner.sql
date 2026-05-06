-- V8: Phase 3 features — terrarium evolution stages, custom categories,
-- attendance check-in, joint records, free placement entitlement.
--
-- All changes are additive (nullable columns + new tables). Existing rows
-- get sane defaults so that pre-V8 BE pods serving in-flight requests during
-- a rolling deploy don't observe FK violations or NOT NULL breakage.

-- 1) Terrarium 5-stage evolution
ALTER TABLE terrariums
    ADD COLUMN evolution_stage VARCHAR(20) NOT NULL DEFAULT 'BOTTLE';

-- 2) Custom categories — owner_user_id NULL means system category
ALTER TABLE categories
    ADD COLUMN is_custom BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN owner_user_id TEXT REFERENCES users(id) ON DELETE CASCADE;

CREATE INDEX idx_categories_owner ON categories(owner_user_id) WHERE owner_user_id IS NOT NULL;

-- Mark seeded 4 categories as system (idempotent in case partial state exists)
UPDATE categories SET is_custom = FALSE, owner_user_id = NULL WHERE name IN ('산책','독서','러닝','낙서');

-- 3) Attendance log (check-in history)
CREATE TABLE attendance_logs (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    check_in_date DATE NOT NULL,
    streak INTEGER NOT NULL,
    reward_basic_coins INTEGER NOT NULL,
    bonus BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, check_in_date)
);
CREATE INDEX idx_attendance_user_date ON attendance_logs(user_id, check_in_date DESC);

-- 4) Joint record — partner_user_id is the OTHER user; both records share the same
-- session_id so we can group "I and a friend recorded together" pairs in queries.
ALTER TABLE activity_records
    ADD COLUMN partner_user_id TEXT REFERENCES users(id) ON DELETE SET NULL,
    ADD COLUMN joint_session_id UUID;

CREATE INDEX idx_records_partner ON activity_records(partner_user_id) WHERE partner_user_id IS NOT NULL;
CREATE INDEX idx_records_joint_session ON activity_records(joint_session_id) WHERE joint_session_id IS NOT NULL;

-- 5) Free placement + premium themes entitlement
ALTER TABLE users
    ADD COLUMN entitlement_free_placement BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN entitlement_premium_themes BOOLEAN NOT NULL DEFAULT FALSE;
