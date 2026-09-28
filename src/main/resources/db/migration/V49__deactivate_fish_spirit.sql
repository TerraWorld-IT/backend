-- 피그마·드라이브에 근거가 없는 물고기 정령의 판매·신규 지급을 중단한다.
-- 기존 user_items / user_characters / terrarium_items 소유·배치 행은 보존한다.
UPDATE items
SET is_active = FALSE,
    purchasable = FALSE
WHERE slug = 'fish-spirit';

-- character_defs에는 활성 플래그가 없으므로 획득 경로를 비활성으로 표시한다.
UPDATE character_defs
SET sellable = FALSE,
    acquire_source = 'DISABLED'
WHERE code = 'fish';

-- 기존 티어와 해금 상태는 유지하고 앞으로 지급할 보상 연결만 해제한다.
UPDATE tier_configs
SET spirit_code = NULL
WHERE spirit_code = 'fish';
