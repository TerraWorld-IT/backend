-- N9 (구현 계획서 v4, 2026-05-26): AdMob reward nonce inbox.
--
-- 변경 사유: RewardService.claimAdReward 가 광고 시청 증명 (nonce) 없이 즉시 specialCoin
-- 지급 → 위조 reward 호출 가능. Phase 4 출시 직전 외부 AdMob ID 주입 전이라도 client-issued
-- nonce 의 one-time-use guarantee 만으로 단순 replay attack 차단 가능.
--
-- Codex audit (Q5) 반영: nonce 의 lifecycle 을 명시 분리 (created_at / expires_at /
-- consumed_at) — 향후 server-issued pending nonce 도입 시 TTL 검증 가능. transaction_id
-- 별도 컬럼 — AdMob SSV callback 의 transaction_id 와 client nonce 를 매핑.
--
-- Algorithm note (Codex Q4): AdMob SSV signature 검증은 ECDSA P-256 SHA-256 (Google 공식
-- spec). 검증 자체는 본 schema 가 아닌 backend layer 에서 별 endpoint
-- (`/rewards/ad/ssv-callback`) 의 raw query string 으로 처리. 본 inbox 는 dedup 만.
--
-- 외부 stakeholder 영역 (이재석 / 운영자): AdMob production ID + SSV callback URL configure
-- 후 환경변수 `ADMOB_SSV_PUBLIC_KEY_URL` 활성화 + `reward.ad.nonce.required=true` 전환.

CREATE TABLE IF NOT EXISTS ad_reward_nonce_inbox (
    nonce            VARCHAR(64)  PRIMARY KEY,
    user_id          VARCHAR(64)  NOT NULL,
    source           VARCHAR(16)  NOT NULL DEFAULT 'CLIENT',  -- CLIENT | SSV_CALLBACK
    transaction_id   VARCHAR(128),                            -- AdMob SSV transaction_id (CLIENT 발급 nonce 도 같은 inbox 공유)
    created_at       TIMESTAMP    NOT NULL DEFAULT NOW(),     -- nonce 인지 시각 (client 호출 도착)
    expires_at       TIMESTAMP,                               -- server-issued pending nonce TTL (CLIENT-issued 는 null)
    consumed_at      TIMESTAMP    NOT NULL DEFAULT NOW(),     -- 보상 지급 완료 시각

    -- N9 Codex audit MEDIUM 3 정정: source 컬럼 허용값 강제 + SSV_CALLBACK 이면 transaction_id 필수.
    -- 잘못된 source 문자열이나 SSV callback 이 transaction_id 없이 들어오는 케이스 차단.
    CONSTRAINT chk_ad_reward_nonce_source CHECK (source IN ('CLIENT', 'SSV_CALLBACK')),
    CONSTRAINT chk_ad_reward_nonce_ssv_tx
        CHECK (source <> 'SSV_CALLBACK' OR transaction_id IS NOT NULL)
);

CREATE INDEX IF NOT EXISTS idx_ad_reward_nonce_user ON ad_reward_nonce_inbox(user_id);
CREATE INDEX IF NOT EXISTS idx_ad_reward_nonce_consumed ON ad_reward_nonce_inbox(consumed_at);
CREATE UNIQUE INDEX IF NOT EXISTS uq_ad_reward_nonce_tx_id
    ON ad_reward_nonce_inbox(transaction_id)
    WHERE transaction_id IS NOT NULL;

COMMENT ON TABLE ad_reward_nonce_inbox IS
    'N9: AdMob reward nonce 중복 사용 dedup. 같은 nonce 로 두 번 claim 시 INSERT conflict 로 throw';
COMMENT ON COLUMN ad_reward_nonce_inbox.source IS
    'CLIENT = 클라이언트 발급 (외부 secret 없는 backward-compat 경로), SSV_CALLBACK = AdMob server callback 검증';
COMMENT ON COLUMN ad_reward_nonce_inbox.transaction_id IS
    'AdMob SSV transaction_id — CLIENT nonce 와 SSV callback nonce 의 매핑. unique partial index';
COMMENT ON COLUMN ad_reward_nonce_inbox.expires_at IS
    'server-issued pending nonce 용 TTL (현 cycle 미사용, CLIENT-issued 는 null)';
