-- V22: 상점 아이템 카탈로그 + 토큰 교환 비율 시드 (2026-06-23, 사용자 결정)
--
-- 배경: 부팅 직후 items / token_exchange_rates 테이블이 비어 있어 (1) 상점이 빈 카탈로그,
--       (2) token→token 환전이 EXCHANGE_RATE_NOT_FOUND 로 전부 실패하는 상태였음.
--
-- 출처(SoT): 디자인 프로토타입 reference/Figma0409/src/app/lib/items.ts (SHOP_ITEMS 40종).
--   가격/희귀도/레이아웃/카테고리는 프로토타입을 그대로 derive.
--
-- ⚠️ asset_url = 임시 이모지(프로토타입 `image` 필드). 현재 프론트(ShopContent/ItemSelectDialog/
--   terrarium/index.vue)는 assetUrl 을 <img> 가 아니라 **텍스트 glyph**(text-4xl)로 렌더하므로,
--   경로 문자열을 넣으면 "/items/x.png" 가 그대로 노출된다. 따라서 이모지를 직접 저장 → 화면에 정상 표시.
--   실 리소페인트 스탬프 PNG 준비 시: (a) asset_url 을 경로로 교체 + (b) 프론트 렌더를 isUrl 분기
--   img 로 전환(index.vue 는 이미 isUrl 분기 존재)을 같은 PR 로 동반할 것.
--
-- 매핑: priceType basic→BASIC / special→SPECIAL / mixed→MIXED, rarity common→COMMON/rare→RARE/
--       epic→EPIC, layout foreground→FOREGROUND/background→BACKGROUND/figure→FIGURE.
--   category_id 는 이름 JOIN 으로 해석(id 하드코딩 회피). slug UNIQUE + ON CONFLICT DO NOTHING 으로 멱등.

INSERT INTO items (slug, name, category_id, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated)
SELECT v.slug, v.name, c.id, v.price_type, v.price_amount, v.token_price, v.rarity, v.asset_url, v.layout, v.is_animated
FROM (
    VALUES
        -- 산책 (식물)
        ('plant-1', '작은 선인장', '산책', 'BASIC', 30, NULL::INTEGER, 'COMMON', '🌵', 'FOREGROUND', FALSE),
        ('plant-2', '행운목', '산책', 'BASIC', 40, NULL, 'COMMON', '🌿', 'BACKGROUND', FALSE),
        ('plant-3', '분재나무', '산책', 'MIXED', 80, 15, 'RARE', '🌳', 'BACKGROUND', FALSE),
        ('plant-4', '벚꽃나무', '산책', 'SPECIAL', 150, NULL, 'EPIC', '🌸', 'BACKGROUND', TRUE),
        -- 독서 (식물)
        ('book-1', '책 한 권', '독서', 'BASIC', 25, NULL, 'COMMON', '📕', 'FOREGROUND', FALSE),
        ('book-2', '책더미', '독서', 'MIXED', 60, 10, 'COMMON', '📚', 'FOREGROUND', FALSE),
        ('book-3', '작은 서재', '독서', 'MIXED', 90, 20, 'RARE', '📖', 'FOREGROUND', FALSE),
        ('book-4', '마법서', '독서', 'SPECIAL', 200, NULL, 'EPIC', '✨', 'FOREGROUND', TRUE),
        -- 러닝 (식물)
        ('run-1', '운동화', '러닝', 'BASIC', 35, NULL, 'COMMON', '👟', 'BACKGROUND', FALSE),
        ('run-2', '트로피', '러닝', 'BASIC', 50, NULL, 'COMMON', '🏆', 'BACKGROUND', FALSE),
        ('run-3', '메달', '러닝', 'SPECIAL', 100, NULL, 'RARE', '🥇', 'FOREGROUND', FALSE),
        ('run-4', '불타는 러닝화', '러닝', 'SPECIAL', 180, NULL, 'EPIC', '🔥', 'FOREGROUND', TRUE),
        -- 낙서 (식물)
        ('draw-1', '크레용', '낙서', 'BASIC', 20, NULL, 'COMMON', '🖍️', 'BACKGROUND', FALSE),
        ('draw-2', '팔레트', '낙서', 'BASIC', 45, NULL, 'COMMON', '🎨', 'FOREGROUND', FALSE),
        ('draw-3', '그림 액자', '낙서', 'MIXED', 75, 18, 'RARE', '🖼️', 'FOREGROUND', FALSE),
        ('draw-4', '무지개 붓', '낙서', 'SPECIAL', 160, NULL, 'EPIC', '🌈', 'FOREGROUND', TRUE),
        -- 기본 코인/소품
        ('rock-1', '조약돌', '산책', 'BASIC', 50, NULL, 'COMMON', '🪨', 'FOREGROUND', FALSE),
        ('lamp-1', '작은 램프', '독서', 'BASIC', 80, NULL, 'COMMON', '💡', 'FOREGROUND', FALSE),
        ('coin-flower', '해바라기', '산책', 'MIXED', 70, 12, 'COMMON', '🌻', 'BACKGROUND', FALSE),
        ('coin-mushroom', '버섯', '산책', 'BASIC', 120, NULL, 'RARE', '🍄', 'BACKGROUND', FALSE),
        ('coin-crystal', '수정', '독서', 'SPECIAL', 150, NULL, 'RARE', '💎', 'FOREGROUND', FALSE),
        ('coin-feather', '깃털', '러닝', 'BASIC', 90, NULL, 'COMMON', '🪶', 'FOREGROUND', FALSE),
        ('coin-shell', '조개껍질', '낙서', 'MIXED', 65, 15, 'COMMON', '🐚', 'FOREGROUND', FALSE),
        ('coin-butterfly', '나비', '산책', 'SPECIAL', 200, NULL, 'EPIC', '🦋', 'FOREGROUND', TRUE),
        -- 피규어 (산책)
        ('figure-dog', '강아지', '산책', 'BASIC', 40, NULL, 'COMMON', '🐕', 'FIGURE', FALSE),
        ('figure-cat', '고양이', '산책', 'BASIC', 35, NULL, 'COMMON', '🐈', 'FIGURE', FALSE),
        ('figure-rabbit', '토끼', '산책', 'MIXED', 85, 20, 'RARE', '🐰', 'FIGURE', FALSE),
        ('figure-deer', '사슴', '산책', 'SPECIAL', 170, NULL, 'EPIC', '🦌', 'FIGURE', FALSE),
        -- 피규어 (독서)
        ('figure-owl', '부엉이', '독서', 'BASIC', 45, NULL, 'COMMON', '🦉', 'FIGURE', FALSE),
        ('figure-penguin', '펭귄', '독서', 'MIXED', 50, 8, 'COMMON', '🐧', 'FIGURE', FALSE),
        ('figure-fox', '여우', '독서', 'MIXED', 95, 22, 'RARE', '🦊', 'FIGURE', FALSE),
        ('figure-unicorn', '유니콘', '독서', 'SPECIAL', 190, NULL, 'EPIC', '🦄', 'FIGURE', TRUE),
        -- 피규어 (러닝)
        ('figure-horse', '말', '러닝', 'MIXED', 55, 10, 'COMMON', '🐴', 'FIGURE', FALSE),
        ('figure-tiger', '호랑이', '러닝', 'BASIC', 48, NULL, 'COMMON', '🐯', 'FIGURE', FALSE),
        ('figure-lion', '사자', '러닝', 'SPECIAL', 110, NULL, 'RARE', '🦁', 'FIGURE', FALSE),
        ('figure-dragon', '드래곤', '러닝', 'SPECIAL', 210, NULL, 'EPIC', '🐉', 'FIGURE', TRUE),
        -- 피규어 (낙서)
        ('figure-bear', '곰', '낙서', 'BASIC', 38, NULL, 'COMMON', '🐻', 'FIGURE', FALSE),
        ('figure-panda', '판다', '낙서', 'MIXED', 60, 12, 'COMMON', '🐼', 'FIGURE', FALSE),
        ('figure-koala', '코알라', '낙서', 'MIXED', 105, 25, 'RARE', '🐨', 'FIGURE', FALSE),
        ('figure-phoenix', '불사조', '낙서', 'SPECIAL', 195, NULL, 'EPIC', '🔥', 'FIGURE', TRUE)
) AS v(slug, name, cat_name, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated)
JOIN categories c ON c.name = v.cat_name
ON CONFLICT (slug) DO NOTHING;

-- 토큰 교환 비율: 시스템 카테고리 4종 간 token→token 1:1 (프로토타입 EXCHANGE_RATE_TOKEN=1).
-- 시스템 카테고리(이름 고정 4종)만 — 사용자 커스텀 카테고리 토큰 환율은 admin 이 별도 등록.
INSERT INTO token_exchange_rates (from_category_id, to_category_id, rate, is_active)
SELECT f.id, t.id, 1.0, TRUE
FROM categories f
CROSS JOIN categories t
WHERE f.id <> t.id
  AND f.name IN ('산책', '독서', '러닝', '낙서')
  AND t.name IN ('산책', '독서', '러닝', '낙서')
ON CONFLICT (from_category_id, to_category_id) DO NOTHING;

-- 방어 가드(silent 0-row 회귀 차단): categories 이름 JOIN 이 실패하면 INSERT 가 에러 없이 0 row 가
-- 되어 "마이그레이션 성공인데 빈 상점" silent gap 이 된다(리뷰 IAP-008/silent-M1). 시드 결과를 검증.
DO $$
DECLARE
    item_count INT;
    rate_count INT;
BEGIN
    SELECT COUNT(*) INTO item_count FROM items WHERE slug LIKE 'plant-%' OR slug LIKE 'figure-%';
    IF item_count = 0 THEN
        RAISE EXCEPTION 'V22 seed: items 0건 — categories(산책/독서/러닝/낙서) 이름 JOIN 실패 가능성';
    END IF;
    SELECT COUNT(*) INTO rate_count FROM token_exchange_rates;
    IF rate_count = 0 THEN
        RAISE EXCEPTION 'V22 seed: token_exchange_rates 0건 — categories 이름 JOIN 실패 가능성';
    END IF;
END $$;
