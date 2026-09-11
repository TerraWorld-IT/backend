-- 계정 삭제 트랜잭션과 함께 사진 정리 대상을 보존한다.
CREATE TABLE photo_deletion_outbox (
    public_url TEXT PRIMARY KEY,
    attempts INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT now(),
    last_attempt_at TIMESTAMP NULL
);
