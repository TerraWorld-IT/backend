-- N3 (구현 계획서 v4, 2026-05-21): entitlement SoT 일원화 backfill
--
-- 변경 사유: entitlement 가 두 곳에서 관리되던 split-brain 해소.
--   - 이전 SoT 1: users.entitlement_free_placement / entitlement_premium_themes (boolean 컬럼)
--   - 이전 SoT 2: user_entitlement 테이블 (V13, IAP webhook 이 grant/revoke)
--   - CUSTOM 진화 / UserMeResponse 가 boolean 만 조회 → IAP grant 와 불일치 가능.
--
-- 본 migration: deprecated boolean 컬럼의 true row 를 user_entitlement + entitlement_event
-- GRANT ledger 로 이관. 이후 entitlement 읽기는 EntitlementService.hasEntitlement() 단일 경로.
-- boolean 컬럼 자체는 본 migration 에서 drop 하지 않음 (backfill 검증 후 별 migration).

-- ========================================
-- 1) free_placement backfill
-- ========================================
INSERT INTO user_entitlement (user_id, entitlement_key, granted_at, tx_ref)
SELECT id, 'free_placement', NOW(), NULL
FROM users
WHERE entitlement_free_placement = TRUE
ON CONFLICT (user_id, entitlement_key) DO NOTHING;

INSERT INTO entitlement_event (user_id, entitlement_key, action, reason, tx_ref, occurred_at)
SELECT u.id, 'free_placement', 'GRANT', 'SYSTEM_GRANT', NULL, NOW()
FROM users u
WHERE u.entitlement_free_placement = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM entitlement_event e
      WHERE e.user_id = u.id AND e.entitlement_key = 'free_placement' AND e.action = 'GRANT'
  );

-- ========================================
-- 2) premium_themes backfill
-- ========================================
INSERT INTO user_entitlement (user_id, entitlement_key, granted_at, tx_ref)
SELECT id, 'premium_themes', NOW(), NULL
FROM users
WHERE entitlement_premium_themes = TRUE
ON CONFLICT (user_id, entitlement_key) DO NOTHING;

INSERT INTO entitlement_event (user_id, entitlement_key, action, reason, tx_ref, occurred_at)
SELECT u.id, 'premium_themes', 'GRANT', 'SYSTEM_GRANT', NULL, NOW()
FROM users u
WHERE u.entitlement_premium_themes = TRUE
  AND NOT EXISTS (
      SELECT 1 FROM entitlement_event e
      WHERE e.user_id = u.id AND e.entitlement_key = 'premium_themes' AND e.action = 'GRANT'
  );
