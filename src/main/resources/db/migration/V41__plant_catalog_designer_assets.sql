-- V41: 디자이너 확정 식물 아이템 카탈로그 13종 + 프로토타입 이모지 아이템 비활성화 (2026-08-26)
--
-- 배경: V22 카탈로그(프로토타입 SHOP_ITEMS 40종)는 asset_url 이 이모지였고 Figma(8/21) 상점의
--       식물 카탈로그(이오난사·후마타 고사리·남천·필레아 페페·둥근잎 아카시아 …)와 이름·가격 체계가 다르다.
--       디자이너 제공 PNG 가 frontend/public/items/<slug>.png 로 들어오면서 카탈로그를 Figma 기준으로 맞춘다.
--
-- 규칙:
--   - 가격은 활동 토큰 단독(price_type = TOKEN, 카테고리의 토큰으로 결제) — Figma 카드 "이슬토큰 25".
--     카테고리→토큰 매핑(프론트 utils/shop.ts CATEGORY_TOKEN_CODE): 산책→이슬 / 독서→햇살 / 러닝→번개 / 낙서→바람.
--   - asset_url 은 프론트 규약 경로 `/items/<slug>.png` (useItemAsset: NUXT_PUBLIC_ASSET_BASE 로 CDN 전환).
--   - 기존 이모지 아이템은 삭제하지 않고 is_active = FALSE 로 상점·카탈로그에서만 내린다
--     (user_items / terrarium_items 참조 보존 — 이미 배치된 아이템은 계속 그려진다).
--   - 정령 아이템(cat-spirit)은 디자이너 정령 일러스트로 asset_url 교체, 판매 여부는 그대로(purchasable = FALSE).
--   - slug UNIQUE + ON CONFLICT DO NOTHING 으로 멱등.

-- V22 프로토타입 40종을 slug 로 특정해 내린다 (asset_url 모양으로 고르면 admin 이 등록한 상대경로·대문자 스킴
-- 자산까지 판매 중단되므로 목록을 고정). 이미 admin 이 다른 값으로 바꾼 행도 slug 기준으로 함께 내려간다.
UPDATE items
SET is_active = FALSE
WHERE slug IN (
    'plant-1', 'plant-2', 'plant-3', 'plant-4', 'book-1',
    'book-2', 'book-3', 'book-4', 'run-1', 'run-2',
    'run-3', 'run-4', 'draw-1', 'draw-2', 'draw-3',
    'draw-4', 'rock-1', 'lamp-1', 'coin-flower', 'coin-mushroom',
    'coin-crystal', 'coin-feather', 'coin-shell', 'coin-butterfly', 'figure-dog',
    'figure-cat', 'figure-rabbit', 'figure-deer', 'figure-owl', 'figure-penguin',
    'figure-fox', 'figure-unicorn', 'figure-horse', 'figure-tiger', 'figure-lion',
    'figure-dragon', 'figure-bear', 'figure-panda', 'figure-koala', 'figure-phoenix'
);

UPDATE items SET asset_url = '/spirits/stage2.png' WHERE slug = 'cat-spirit';

INSERT INTO items (slug, name, category_id, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated)
SELECT v.slug, v.name, c.id, 'TOKEN', v.price_amount, NULL, v.rarity, '/items/' || v.slug || '.png', 'FOREGROUND', FALSE
FROM (
    VALUES
        -- 산책 → 이슬토큰
        ('tillandsia-ionantha', '이오난사',        '산책', 25, 'COMMON'),
        ('round-leaf-acacia',   '둥근잎 아카시아', '산책', 35, 'COMMON'),
        ('wind-orchid',         '풍란',            '산책', 30, 'COMMON'),
        ('weed',                '잡초',            '산책', 10, 'COMMON'),
        -- 독서 → 햇살토큰
        ('humata-fern',         '후마타 고사리',   '독서', 30, 'COMMON'),
        ('scindapsus',          '스킨답서스',      '독서', 25, 'COMMON'),
        ('jeju-creeping-fig',   '제주애기모람',    '독서', 35, 'COMMON'),
        -- 러닝 → 번개토큰
        ('pilea-peperomioides', '필레아 페페',     '러닝', 30, 'COMMON'),
        ('dischidia',           '콩란',            '러닝', 25, 'COMMON'),
        ('red-star',            '레드스타',        '러닝', 35, 'RARE'),
        -- 낙서 → 바람토큰
        ('nandina',             '남천',            '낙서', 30, 'COMMON'),
        ('pteris',              '프테리스',        '낙서', 35, 'COMMON'),
        ('stephania',           '스테파니아',      '낙서', 40, 'RARE')
) AS v(slug, name, cat_name, price_amount, rarity)
JOIN categories c ON c.name = v.cat_name AND c.is_custom = FALSE
ON CONFLICT (slug) DO NOTHING;

-- 방어 가드: 카테고리 이름 JOIN 이 실패하면 INSERT 가 에러 없이 0 row 가 되어 상점이 비어 버리고,
-- slug 가 선점돼 ON CONFLICT 로 건너뛴 행은 가격·카테고리·자산 경로가 다를 수 있다 — 13종 전부가
-- 목표 상태(TOKEN 가격·활성·규약 경로·시스템 카테고리)인지 센다.
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n
    FROM items i
    JOIN categories c ON c.id = i.category_id AND c.is_custom = FALSE
    WHERE i.slug IN (
        'tillandsia-ionantha', 'round-leaf-acacia', 'wind-orchid', 'weed',
        'humata-fern', 'scindapsus', 'jeju-creeping-fig',
        'pilea-peperomioides', 'dischidia', 'red-star',
        'nandina', 'pteris', 'stephania'
    )
      AND i.price_type = 'TOKEN'
      AND i.is_active = TRUE
      AND i.purchasable = TRUE
      AND i.asset_url = '/items/' || i.slug || '.png';
    IF n <> 13 THEN
        RAISE EXCEPTION 'V41 seed: 식물 아이템이 목표 상태가 아님(%/13) — categories 이름 JOIN 실패 또는 slug 선점 행 불일치', n;
    END IF;
END $$;
