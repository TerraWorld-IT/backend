-- V43 (계정 삭제, 2026-09-09): 사용자 FK 누락 테이블의 고아 정리와 동시 쓰기 차단.
-- 기존 고아 행을 먼저 제거한 뒤 FK를 추가한다. 습관 하위 사이클·페어 요청은 tracker FK로 cascade된다.

-- ========================================
-- 1) 기존 고아 개인정보 정리
-- ========================================
DELETE FROM user_devices WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM habit_trackers WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM ad_reward_nonce_inbox WHERE user_id NOT IN (SELECT id FROM users);
DELETE FROM exchange_daily_usage WHERE user_id NOT IN (SELECT id FROM users);

-- ========================================
-- 2) 사용자 삭제와 동시 INSERT의 참조 무결성 보장
-- ========================================
ALTER TABLE user_devices
    ADD CONSTRAINT fk_user_devices_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE habit_trackers
    ADD CONSTRAINT fk_habit_trackers_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE ad_reward_nonce_inbox
    ADD CONSTRAINT fk_ad_reward_nonce_inbox_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE exchange_daily_usage
    ADD CONSTRAINT fk_exchange_daily_usage_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
