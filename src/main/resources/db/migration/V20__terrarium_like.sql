-- P-FRIEND-001 (구현 계획서 v4, 2026-05-21): 친구 테라리움 좋아요.
--
-- 친구 관계 자체는 별도 테이블 불필요 — 수락된 invite (Invite.acceptedAt IS NOT NULL,
-- inviter/invitee 양방향) 가 이미 친구 관계의 SoT. 본 테이블은 "놀러가기" 화면의
-- 좋아요만 추적 (liker 1명당 target 1회, 토글).

CREATE TABLE IF NOT EXISTS terrarium_like (
    id              BIGSERIAL    PRIMARY KEY,
    liker_user_id   VARCHAR(255) NOT NULL,
    target_user_id  VARCHAR(255) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_terrarium_like UNIQUE (liker_user_id, target_user_id),
    CONSTRAINT fk_tlike_liker  FOREIGN KEY (liker_user_id)  REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_tlike_target FOREIGN KEY (target_user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_terrarium_like_target ON terrarium_like(target_user_id);

COMMENT ON TABLE terrarium_like IS
    'P-FRIEND-001: 친구 테라리움 좋아요. liker×target 1행 (토글)';
