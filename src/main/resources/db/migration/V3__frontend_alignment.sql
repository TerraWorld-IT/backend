-- V3: Align schema with frontend data model

-- 1. users: add special_coin
ALTER TABLE users ADD COLUMN special_coin BIGINT NOT NULL DEFAULT 0;

-- 2. items: add frontend-aligned columns
ALTER TABLE items ADD COLUMN slug VARCHAR(50) UNIQUE;
ALTER TABLE items ADD COLUMN token_price INTEGER;
ALTER TABLE items ADD COLUMN layout VARCHAR(20) NOT NULL DEFAULT 'FOREGROUND';
ALTER TABLE items ADD COLUMN is_animated BOOLEAN NOT NULL DEFAULT FALSE;

-- 3. categories: add emoji
ALTER TABLE categories ADD COLUMN emoji VARCHAR(10);

-- 4. day_notes table
CREATE TABLE day_notes (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    note_date DATE NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, note_date)
);

-- 5. terrarium_items: add slot_id for fixed-slot mode
ALTER TABLE terrarium_items ADD COLUMN slot_id INTEGER;

-- 6. Update category seed data (match frontend rewards)
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='🚶‍♀️' WHERE name='산책';
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='📖' WHERE name='독서';
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='👟' WHERE name='러닝';
UPDATE categories SET base_coin_reward=20, base_token_reward=10, emoji='🎨' WHERE name='낙서';
