-- V9: Audit Log infrastructure
--
-- 변경 내용 (잔고/토큰/보상/구매/출석/초대 등) 의 사후 추적/포렌식을 위한 테이블.
-- 모든 컬럼은 best-effort 로 채워지며, payload 는 애플리케이션 측 JSON 직렬화.
-- payload 내에는 PII 가 들어가지 않게 publisher 측에서 책임진다.

CREATE TABLE IF NOT EXISTS audit_logs (
  id              BIGSERIAL PRIMARY KEY,
  user_id         TEXT        NOT NULL,
  action          VARCHAR(64) NOT NULL,
  resource_type   VARCHAR(64),
  resource_id     VARCHAR(128),
  payload         JSONB,
  created_at      TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_audit_logs_user_id_created_at
  ON audit_logs (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_action_created_at
  ON audit_logs (action, created_at DESC);

COMMENT ON TABLE audit_logs IS '잔고/보상/구매/구조 변경 이벤트의 commit-after audit 로그';
COMMENT ON COLUMN audit_logs.action IS 'REWARD_AD_CLAIMED / EXCHANGE_S2B / EXCHANGE_TOKEN / PURCHASE_ITEM / ATTENDANCE_CHECKIN ...';
COMMENT ON COLUMN audit_logs.payload IS 'JSON: {amount, beforeBalance, afterBalance, ...} — PII 금지';
