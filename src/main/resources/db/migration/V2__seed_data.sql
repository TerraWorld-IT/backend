-- V2: Seed categories, level config, default background

INSERT INTO categories (name, icon_url, color, token_name, base_coin_reward, base_token_reward, daily_limit) VALUES
    ('산책', '/icons/walk.svg', '#00A95C', '산책토큰', 10, 5, 5),
    ('독서', '/icons/read.svg', '#0078BF', '독서토큰', 10, 5, 5),
    ('러닝', '/icons/run.svg', '#E3505F', '러닝토큰', 15, 7, 3),
    ('낙서', '/icons/draw.svg', '#FF6C2F', '낙서토큰', 10, 5, 5);

INSERT INTO terrarium_backgrounds (name, asset_url, unlock_condition, unlock_value) VALUES
    ('기본 유리병', '/backgrounds/default.png', 'DEFAULT', 0),
    ('둥근 어항', '/backgrounds/round.png', 'LEVEL', 5),
    ('큰 화분', '/backgrounds/pot.png', 'LEVEL', 10);

INSERT INTO level_configs (level, required_exp, reward_type, reward_value, max_items) VALUES
    (1, 0, NULL, NULL, 10),
    (2, 100, 'MAX_ITEMS_UP', 5, 15),
    (3, 300, 'MAX_ITEMS_UP', 5, 20),
    (4, 600, 'BACKGROUND_UNLOCK', 2, 20),
    (5, 1000, 'MAX_ITEMS_UP', 10, 30),
    (6, 1500, NULL, NULL, 30),
    (7, 2100, NULL, NULL, 30),
    (8, 2800, NULL, NULL, 30),
    (9, 3600, NULL, NULL, 30),
    (10, 4500, 'BACKGROUND_UNLOCK', 3, 40);
