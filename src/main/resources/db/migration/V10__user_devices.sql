-- V10: 푸시 디바이스 토큰 등록 테이블
--
-- (user_id, token) unique → 멱등 upsert 가능. invalidate 토큰은
-- 푸시 발송 시 서버가 is_active=false 로 마킹.

CREATE TABLE IF NOT EXISTS user_devices (
  id              BIGSERIAL  PRIMARY KEY,
  user_id         TEXT       NOT NULL,
  token           TEXT       NOT NULL,
  platform        VARCHAR(16) NOT NULL,
  app_version     VARCHAR(32),
  last_seen_at    TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  is_active       BOOLEAN    NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMP  NOT NULL DEFAULT CURRENT_TIMESTAMP,
  CONSTRAINT user_devices_user_token_unique UNIQUE (user_id, token)
);

-- 사용자 별 활성 디바이스 조회 (캠페인 발송 시).
CREATE INDEX IF NOT EXISTS idx_user_devices_user_active
  ON user_devices (user_id) WHERE is_active = TRUE;

-- platform 별 통계.
CREATE INDEX IF NOT EXISTS idx_user_devices_platform_active
  ON user_devices (platform) WHERE is_active = TRUE;

COMMENT ON TABLE user_devices IS '푸시 발송용 사용자 디바이스 토큰 (FCM/APNs)';
COMMENT ON COLUMN user_devices.is_active IS 'invalidate 된 토큰은 false 로 마킹 — DELETE 하지 않고 audit 가능 상태로 보존';
