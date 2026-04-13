-- V4: Migrate users.id from BIGSERIAL → TEXT (better-auth CUID)
--
-- better-auth (Nuxt/Nitro) now owns user identity in the `auth.user` table.
-- Spring's `public.users` becomes a domain profile table keyed by the same
-- CUID value. A hard FK to `auth.user(id)` is added later in V5 once the
-- better-auth schema is guaranteed to exist on the target DB.
--
-- Destructive: this DROPs and recreates the user table + all 7 FK-dependent
-- tables. Safe ONLY when `public.users` is empty.
--
-- SEC-008: guard against destructive re-run on a populated DB. Any real
-- user data triggers an immediate abort so DR drills, staging promotions,
-- and accidental re-baselining cannot silently erase wallets/terrariums.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.tables
        WHERE table_schema = 'public' AND table_name = 'users'
    ) THEN
        IF EXISTS (SELECT 1 FROM public.users LIMIT 1) THEN
            RAISE EXCEPTION
                'V4 refuses to run: public.users has data. This migration is '
                'destructive (drops and recreates user tables). Back up, '
                'reconcile manually, then rebaseline Flyway to version 4.';
        END IF;
    END IF;
END $$;

-- 1. Drop user-dependent tables (CASCADE handles inter-dependencies)
DROP TABLE IF EXISTS day_notes CASCADE;
DROP TABLE IF EXISTS terrarium_items CASCADE;
DROP TABLE IF EXISTS terrariums CASCADE;
DROP TABLE IF EXISTS user_items CASCADE;
DROP TABLE IF EXISTS user_tokens CASCADE;
DROP TABLE IF EXISTS wallet_transactions CASCADE;
DROP TABLE IF EXISTS activity_records CASCADE;
DROP TABLE IF EXISTS users CASCADE;

-- 2. Recreate users — TEXT id, no email/password (owned by auth.users)
CREATE TABLE users (
    id TEXT PRIMARY KEY,
    nickname VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    level INTEGER NOT NULL DEFAULT 1,
    total_exp BIGINT NOT NULL DEFAULT 0,
    basic_coin BIGINT NOT NULL DEFAULT 0,
    special_coin BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 3. Recreate user-dependent tables with TEXT user_id

CREATE TABLE activity_records (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id),
    memo TEXT,
    recorded_date DATE NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_records_user_date ON activity_records(user_id, recorded_date);

CREATE TABLE user_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    category_id BIGINT NOT NULL REFERENCES categories(id),
    amount BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(user_id, category_id)
);

CREATE TABLE wallet_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    currency_type VARCHAR(30) NOT NULL,
    category_id BIGINT REFERENCES categories(id),
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    reference_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_wallet_tx_user ON wallet_transactions(user_id, created_at DESC);

CREATE TABLE user_items (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    acquired_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE UNIQUE INDEX idx_user_items_unique ON user_items(user_id, item_id);

CREATE TABLE terrariums (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL UNIQUE REFERENCES users(id) ON DELETE CASCADE,
    background_id BIGINT NOT NULL REFERENCES terrarium_backgrounds(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE terrarium_items (
    id BIGSERIAL PRIMARY KEY,
    terrarium_id BIGINT NOT NULL REFERENCES terrariums(id) ON DELETE CASCADE,
    item_id BIGINT NOT NULL REFERENCES items(id),
    slot_id INTEGER,
    pos_x FLOAT NOT NULL,
    pos_y FLOAT NOT NULL,
    rotation FLOAT NOT NULL DEFAULT 0,
    scale FLOAT NOT NULL DEFAULT 1.0,
    z_index INTEGER NOT NULL DEFAULT 0,
    placed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE day_notes (
    id BIGSERIAL PRIMARY KEY,
    user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    note_date DATE NOT NULL,
    note TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
    UNIQUE(user_id, note_date)
);
