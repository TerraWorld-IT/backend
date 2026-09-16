-- V47: 디자이너 배경 아이템 "핑크 배경" 시드 (2026-09-16)
--
-- 배경: 디자이너 Drive 의 병1/병2/병3_배경_핑크.png 는 병 뒤 그림이 아니라 **병 본체의 핑크 색 변형**(레벨별 1장)이다.
--       프론트는 BACKGROUND 아이템 slug `bg-<variant>` 를 병 에셋 `/jar/lv{N}-<variant>.webp` 로 해석한다
--       (frontend utils/jarVariants). 상점 썸네일은 아이템 규약 경로 `/items/bg-pink.png`.
--
-- 규칙(V39 배경 시드와 동일):
--   - layout BACKGROUND, purchasable TRUE, 코인(BASIC) 가격. category 는 이름 JOIN(V39 배경과 같은 산책).
--   - slug UNIQUE + ON CONFLICT DO NOTHING 으로 멱등. 가격·이름은 확정 전 임시값 — admin 에서 조정 가능.
INSERT INTO items (slug, name, description, category_id, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated, is_active, purchasable)
SELECT v.slug, v.name, v.description, c.id, v.price_type, v.price_amount, v.token_price, v.rarity, v.asset_url, v.layout, v.is_animated, TRUE, TRUE
FROM (
    VALUES
        ('bg-pink', '핑크 배경', '분홍빛으로 물든 유리병', '산책', 'BASIC', 120, NULL::INTEGER, 'COMMON', '/items/bg-pink.png', 'BACKGROUND', FALSE)
) AS v(slug, name, description, category_name, price_type, price_amount, token_price, rarity, asset_url, layout, is_animated)
JOIN categories c ON c.name = v.category_name AND c.is_custom = FALSE
ON CONFLICT (slug) DO NOTHING;

-- 방어 가드: 카테고리 이름 JOIN 이 실패하면 INSERT 가 에러 없이 0 row 가 된다 — 목표 상태(활성·판매·규약 경로·BACKGROUND)인지 센다.
DO $$
DECLARE
    n INTEGER;
BEGIN
    SELECT COUNT(*) INTO n
    FROM items i
    WHERE i.slug = 'bg-pink'
      AND i.layout = 'BACKGROUND'
      AND i.is_active = TRUE
      AND i.purchasable = TRUE
      AND i.asset_url = '/items/bg-pink.png';
    IF n <> 1 THEN
        RAISE EXCEPTION 'V47 seed: bg-pink 배경 아이템이 목표 상태가 아님(%/1) — categories 이름 JOIN 실패 또는 slug 선점 행 불일치', n;
    END IF;
END $$;
