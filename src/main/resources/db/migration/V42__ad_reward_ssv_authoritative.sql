-- 광고 보상 nonce 를 서버 발급 및 SSV 검증 수명주기로 확장한다.
-- 기존 CLIENT/SSV_CALLBACK 행은 이미 처리된 기록이므로 CONSUMED 로 유지한다.

ALTER TABLE ad_reward_nonce_inbox
    ALTER COLUMN consumed_at DROP NOT NULL,
    ADD COLUMN status VARCHAR(16) NOT NULL DEFAULT 'CONSUMED',
    ADD COLUMN verified_at TIMESTAMP;

ALTER TABLE ad_reward_nonce_inbox
    DROP CONSTRAINT chk_ad_reward_nonce_source,
    DROP CONSTRAINT chk_ad_reward_nonce_ssv_tx;

ALTER TABLE ad_reward_nonce_inbox
    ADD CONSTRAINT chk_ad_reward_nonce_source
        CHECK (source IN ('CLIENT', 'SSV_CALLBACK', 'SERVER')),
    ADD CONSTRAINT chk_ad_reward_nonce_purpose
        CHECK (purpose IN ('AD_REWARD', 'GROWTH_REVIVE')),
    ADD CONSTRAINT chk_ad_reward_nonce_status
        CHECK (status IN ('PENDING', 'VERIFIED', 'CONSUMED', 'EXPIRED')),
    ADD CONSTRAINT chk_ad_reward_nonce_ssv_tx
        CHECK (source <> 'SSV_CALLBACK' OR transaction_id IS NOT NULL),
    ADD CONSTRAINT chk_ad_reward_nonce_server_expiry
        CHECK (source <> 'SERVER' OR expires_at IS NOT NULL),
    ADD CONSTRAINT chk_ad_reward_nonce_lifecycle
        CHECK (
            (status = 'PENDING'
                AND source = 'SERVER'
                AND transaction_id IS NULL
                AND verified_at IS NULL
                AND consumed_at IS NULL)
            OR
            (status = 'VERIFIED'
                AND source = 'SERVER'
                AND transaction_id IS NOT NULL
                AND verified_at IS NOT NULL
                AND consumed_at IS NULL)
            OR
            (status = 'CONSUMED' AND consumed_at IS NOT NULL)
            OR
            (status = 'EXPIRED' AND source = 'SERVER' AND consumed_at IS NULL)
        );

-- 사용자·용도별 미소비 서버 nonce 를 하나로 제한해 인증 사용자의 발급 반복으로 인한 행 폭증을 막는다.
CREATE UNIQUE INDEX uq_ad_reward_nonce_active_server
    ON ad_reward_nonce_inbox(user_id, purpose)
    WHERE source = 'SERVER' AND status IN ('PENDING', 'VERIFIED');

CREATE INDEX idx_ad_reward_nonce_server_expiry
    ON ad_reward_nonce_inbox(expires_at)
    WHERE source = 'SERVER' AND status IN ('PENDING', 'VERIFIED');

COMMENT ON COLUMN ad_reward_nonce_inbox.status IS
    'SERVER nonce 수명주기: PENDING -> VERIFIED -> CONSUMED, 만료 시 EXPIRED. 기존 행은 CONSUMED';
COMMENT ON COLUMN ad_reward_nonce_inbox.verified_at IS
    'AdMob SSV 서명 검증 및 custom_data nonce 결합 완료 시각';
COMMENT ON COLUMN ad_reward_nonce_inbox.source IS
    'CLIENT=레거시 클라이언트 nonce, SSV_CALLBACK=미결합 SSV 감사 행, SERVER=서버 발급 nonce';
