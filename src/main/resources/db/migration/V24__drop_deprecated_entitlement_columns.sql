-- V24 (P2-1): deprecated entitlement boolean 컬럼 drop.
--
-- 배경: entitlement SoT 는 user_entitlement 테이블(V13) + EntitlementService 로 일원화됨.
--   과거 users.entitlement_free_placement / entitlement_premium_themes boolean 은 V16 에서
--   user_entitlement 로 backfill 됐고, 라이브 read/write 0 (grep 확인). 본 migration 에서 drop.
--
-- 불가역(destructive) 안전장치: DROP 전에 backfill 누락 검사 — boolean=TRUE 인데 user_entitlement
--   에 대응 row 가 없는 사용자가 1명이라도 있으면 RAISE EXCEPTION 으로 **중단**(컬럼 보존).
--   prod 에서 V16 backfill 이 실제 적용됐는지를 migration 자체가 강제(fail-closed) → 운영자가
--   별도 사전 쿼리를 빠뜨려도 데이터 영구 손실 불가.

DO $$
DECLARE
    missing_count BIGINT;
BEGIN
    SELECT count(*) INTO missing_count
    FROM public.users u
    WHERE (
        u.entitlement_free_placement = TRUE
        AND NOT EXISTS (
            SELECT 1 FROM public.user_entitlement ue
            WHERE ue.user_id = u.id AND ue.entitlement_key = 'free_placement'
        )
    ) OR (
        u.entitlement_premium_themes = TRUE
        AND NOT EXISTS (
            SELECT 1 FROM public.user_entitlement ue
            WHERE ue.user_id = u.id AND ue.entitlement_key = 'premium_themes'
        )
    );

    IF missing_count > 0 THEN
        RAISE EXCEPTION
            'V24 중단: deprecated entitlement=TRUE 이나 user_entitlement 에 backfill 안 된 사용자 % 명. V16 backfill 적용 후 재시도 (데이터 손실 방지).',
            missing_count;
    END IF;
END $$;

ALTER TABLE public.users
    DROP COLUMN IF EXISTS entitlement_free_placement,
    DROP COLUMN IF EXISTS entitlement_premium_themes;
