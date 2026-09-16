-- V48: 에셋 없는 플레이스홀더 배경 아이템 4종 비활성화 (2026-09-16)
--
-- 배경: V39 가 시드한 bg-meadow / bg-night / bg-beach / bg-studio 는 asset_url 이 cdn.terraworld.app 플레이스홀더라
--       실제 이미지가 없다(상점·관리 패널에 placeholder.png 로만 보임). 디자이너 배경 아이템(V47 bg-pink)이 들어왔으므로
--       더미 4종은 상점·카탈로그에서 내린다.
--
-- 규칙(V41 프로토타입 비활성화와 동일):
--   - 행을 삭제하지 않고 is_active = FALSE, purchasable = FALSE 로만 내린다
--     (user_items / terrarium_backgrounds 참조 보존 — 이미 소유·설정한 사용자의 상태는 깨지지 않는다).
--   - slug 로 특정한다(asset_url 모양으로 고르면 admin 이 등록한 다른 자산까지 내려간다).
UPDATE items
SET is_active = FALSE,
    purchasable = FALSE
WHERE slug IN ('bg-meadow', 'bg-night', 'bg-beach', 'bg-studio')
  AND layout = 'BACKGROUND';

-- 방어 가드: 4종이 전부 비활성 상태인지, 디자이너 배경(bg-pink)은 그대로 활성인지 센다.
DO $$
DECLARE
    inactive INTEGER;
    pink_active INTEGER;
BEGIN
    SELECT COUNT(*) INTO inactive
    FROM items
    WHERE slug IN ('bg-meadow', 'bg-night', 'bg-beach', 'bg-studio')
      AND is_active = FALSE
      AND purchasable = FALSE;
    IF inactive <> 4 THEN
        RAISE EXCEPTION 'V48: 플레이스홀더 배경 4종이 전부 비활성이 아님(%/4)', inactive;
    END IF;

    SELECT COUNT(*) INTO pink_active
    FROM items
    WHERE slug = 'bg-pink' AND is_active = TRUE AND purchasable = TRUE;
    IF pink_active <> 1 THEN
        RAISE EXCEPTION 'V48: bg-pink 가 활성·판매 상태가 아님(%/1)', pink_active;
    END IF;
END $$;
