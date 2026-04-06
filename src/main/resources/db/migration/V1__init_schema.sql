-- V1: Initial schema

CREATE TABLE users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    nickname VARCHAR(50) NOT NULL,
    role VARCHAR(20) NOT NULL DEFAULT 'USER',
    level INTEGER NOT NULL DEFAULT 1,
    total_exp BIGINT NOT NULL DEFAULT 0,
    basic_coin BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE categories (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL,
    icon_url VARCHAR(500),
    color VARCHAR(7) NOT NULL DEFAULT '#000000',
    token_name VARCHAR(50) NOT NULL,
    base_coin_reward INTEGER NOT NULL DEFAULT 10,
    base_token_reward INTEGER NOT NULL DEFAULT 5,
    daily_limit INTEGER NOT NULL DEFAULT 5,
    is_active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE activity_records (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    category_id BIGINT NOT NULL REFERENCES categories(id),
    memo TEXT,
    recorded_date DATE NOT NULL,
    is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_records_user_date ON activity_records(user_id, recorded_date);

CREATE TABLE user_tokens (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    category_id BIGINT NOT NULL REFERENCES categories(id),
    amount BIGINT NOT NULL DEFAULT 0,
    version BIGINT NOT NULL DEFAULT 0,
    UNIQUE(user_id, category_id)
);

CREATE TABLE wallet_transactions (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    currency_type VARCHAR(30) NOT NULL,
    category_id BIGINT REFERENCES categories(id),
    amount BIGINT NOT NULL,
    balance_after BIGINT NOT NULL,
    reason VARCHAR(50) NOT NULL,
    reference_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallet_tx_user ON wallet_transactions(user_id, created_at DESC);

CREATE TABLE items (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    description TEXT,
    category_id BIGINT REFERENCES categories(id),
    price_type VARCHAR(30) NOT NULL,
    price_amount INTEGER NOT NULL,
    rarity VARCHAR(20) NOT NULL DEFAULT 'COMMON',
    asset_url VARCHAR(500) NOT NULL,
    width INTEGER NOT NULL DEFAULT 512,
    height INTEGER NOT NULL DEFAULT 512,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE user_items (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    quantity INTEGER NOT NULL DEFAULT 1,
    acquired_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_user_items_unique ON user_items(user_id, item_id);

CREATE TABLE terrarium_backgrounds (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    asset_url VARCHAR(500) NOT NULL,
    unlock_condition VARCHAR(30) NOT NULL DEFAULT 'DEFAULT',
    unlock_value INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE terrariums (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE REFERENCES users(id),
    background_id BIGINT NOT NULL REFERENCES terrarium_backgrounds(id),
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE terrarium_items (
    id BIGSERIAL PRIMARY KEY,
    terrarium_id BIGINT NOT NULL REFERENCES terrariums(id),
    item_id BIGINT NOT NULL REFERENCES items(id),
    pos_x FLOAT NOT NULL,
    pos_y FLOAT NOT NULL,
    rotation FLOAT NOT NULL DEFAULT 0,
    scale FLOAT NOT NULL DEFAULT 1.0,
    z_index INTEGER NOT NULL DEFAULT 0,
    placed_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE level_configs (
    level INTEGER PRIMARY KEY,
    required_exp BIGINT NOT NULL,
    reward_type VARCHAR(30),
    reward_value INTEGER,
    max_items INTEGER NOT NULL DEFAULT 20
);

CREATE TABLE token_exchange_rates (
    id BIGSERIAL PRIMARY KEY,
    from_category_id BIGINT NOT NULL REFERENCES categories(id),
    to_category_id BIGINT NOT NULL REFERENCES categories(id),
    rate FLOAT NOT NULL DEFAULT 2.0,
    is_active BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(from_category_id, to_category_id)
);
