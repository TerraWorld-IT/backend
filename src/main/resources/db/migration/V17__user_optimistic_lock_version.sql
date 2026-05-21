-- N10 (구현 계획서 v4, 2026-05-21): 재화/EXP 동시성 — JPA optimistic lock.
--
-- 변경 사유: user.basic_coin / total_exp 등 재화 mutation 에 lock 부재.
-- 동시 record/attendance/purchase 시 lost update 가능 (read-modify-write race).
-- JPA @Version 컬럼 추가 → Hibernate 가 UPDATE 시 version 비교 + 증가.
-- 충돌 시 ObjectOptimisticLockingFailureException → GlobalExceptionHandler 가 409.

ALTER TABLE users
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;

COMMENT ON COLUMN users.version IS
    'N10: JPA optimistic lock version. 동시 재화 변동 lost update 방어';
