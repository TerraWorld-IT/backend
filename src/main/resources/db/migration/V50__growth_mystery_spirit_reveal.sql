-- 수수께끼 정령 공개 정보는 사이클에 고정하고, 기존 진행은 고양이로 보존한다.
ALTER TABLE growth_instances
    ADD COLUMN revealed_spirit_code VARCHAR(32) REFERENCES character_defs(code),
    ADD COLUMN revealed_at TIMESTAMP;

INSERT INTO character_defs (code, name_ko, habitat, acquire_source, sort_order)
VALUES ('snail', '달팽이', 'LAND', 'GROW', 9)
ON CONFLICT (code) DO NOTHING;

UPDATE character_defs SET acquire_source = 'GROW'
WHERE code IN ('cat', 'dino', 'squirrel', 'snail', 'otter', 'axolotl', 'rabbit');

INSERT INTO items (slug, name, description, price_type, price_amount, rarity, asset_url, layout, is_animated, is_active, purchasable)
SELECT code || '-spirit', name, '키우기 30일 완주로 만나는 정령', 'SPECIAL', 0, 'EPIC',
       '/spirits/' || code || '-stage2.png', 'FIGURE', FALSE, TRUE, FALSE
FROM (VALUES
    ('dino', '공룡 정령'),
    ('squirrel', '다람쥐 정령'),
    ('snail', '달팽이 정령'),
    ('otter', '수달 정령'),
    ('axolotl', '아홀로틀 정령'),
    ('rabbit', '토끼 정령')
) AS v(code, name)
ON CONFLICT (slug) DO UPDATE SET
    name = EXCLUDED.name,
    description = EXCLUDED.description,
    price_type = EXCLUDED.price_type,
    price_amount = EXCLUDED.price_amount,
    rarity = EXCLUDED.rarity,
    asset_url = EXCLUDED.asset_url,
    layout = EXCLUDED.layout,
    is_animated = EXCLUDED.is_animated,
    is_active = EXCLUDED.is_active,
    purchasable = EXCLUDED.purchasable;

UPDATE items SET asset_url = '/spirits/pigeon-stage2.png' WHERE slug = 'pigeon-spirit';
UPDATE growth_stages SET label_ko = '1단계' WHERE kind = 'SPIRIT' AND stage_order = 2;
UPDATE growth_stages SET label_ko = '2단계' WHERE kind = 'SPIRIT' AND stage_order = 3;
UPDATE growth_species SET name_ko = '수수께끼 정령' WHERE code = 'cat';

UPDATE growth_instances g
SET revealed_spirit_code = 'cat', revealed_at = (NOW() AT TIME ZONE 'Asia/Seoul'), version = g.version + 1
FROM growth_species s
WHERE s.code = g.species_code AND s.kind = 'SPIRIT'
  AND g.revealed_spirit_code IS NULL
  AND (g.cycle_state = 'COMPLETED' OR g.natural_streak + g.sparkle_bought_count >= (
      SELECT threshold FROM growth_stages WHERE kind = 'SPIRIT' AND stage_order = 2
  ));

-- slug 선점이나 누락을 감지하도록 계획한 7종의 상태를 검사한다.
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n
    FROM items
    WHERE slug IN ('cat-spirit', 'dino-spirit', 'squirrel-spirit', 'snail-spirit', 'otter-spirit', 'axolotl-spirit', 'rabbit-spirit')
      AND layout = 'FIGURE' AND purchasable = FALSE
      AND (slug = 'cat-spirit' OR (
          is_active = TRUE
          AND asset_url = '/spirits/' || REPLACE(slug, '-spirit', '-stage2.png')
      ));
    IF n <> 7 THEN
        RAISE EXCEPTION 'V50: 키우기 정령 아이템이 목표 상태가 아님 (%/7)', n;
    END IF;
END $$;
