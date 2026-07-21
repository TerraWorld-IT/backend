-- 자유배치(free_placement)를 기본 제공으로 전환 — 기존 전 유저 backfill.
--
-- 배경: 낙서장 개편 이후 홈의 유일한 배치 모델이 자유배치인데, 위치 저장이
-- 구 유료화 계획(J-FREE-001, free_placement_unlock ₩1,900)의 entitlement 뒤에
-- 잠겨 있었고 IAP 미활성이라 라이브 전 계정이 배치 저장 불가였다
-- (2026-07-21 사용자 리포트 "테라리움 관리하기에서 저장이 안되는 오류").
--
-- 방식: 게이트(클라 index.vue / 서버 PlacementService)는 유지하고 entitlement 를
-- 기본 부여한다 — 재유료화 시 본 backfill 을 revoke 하고 bootstrap grant 만 제거하면
-- 되는 가역 구조. 신규 유저는 UserBootstrapService 가 가입 시 grant 한다.
--
-- 이벤트 원장은 "이번에 실제로 새로 생긴 row" 에만 기록한다 (CTE RETURNING) —
-- 과거 GRANT→REVOKE 후 재부여되는 유저도 SYSTEM_GRANT 이벤트를 받고,
-- 기존 보유자에게 중복 GRANT 이벤트가 생기지 않는다 (Codex R1 #4).
WITH granted AS (
    INSERT INTO user_entitlement (user_id, entitlement_key)
    SELECT id, 'free_placement'
    FROM users
    ON CONFLICT (user_id, entitlement_key) DO NOTHING
    RETURNING user_id
)
INSERT INTO entitlement_event (user_id, entitlement_key, action, reason)
SELECT user_id, 'free_placement', 'GRANT', 'SYSTEM_GRANT'
FROM granted;

-- 기본 제공 전환에 따라 기존 구매 row 의 tx_ref 를 시스템 부여로 정규화한다.
-- 유지 시 늦게 도착하는 REFUND/CHARGEBACK webhook 이 deleteIfTxRefMatches 로
-- "이제 기본 제공이어야 할" 권리를 삭제해 버린다 (Codex R1 #2 — 기존 유저는
-- bootstrap 을 다시 타지 않아 복구 경로가 없다). 결제 환불 원장은 entitlement
-- row 와 무관하게 tx ledger 에 그대로 남는다.
UPDATE user_entitlement
SET tx_ref = NULL
WHERE entitlement_key = 'free_placement'
  AND tx_ref IS NOT NULL;
