-- V43 (계정 삭제, 2026-09-09): 사용자 FK 누락 테이블의 고아 정리와 동시 쓰기 차단.
-- 기존 고아 행을 먼저 제거한 뒤 FK를 추가한다. 습관 하위 사이클·페어 요청은 tracker FK로 cascade된다.

-- ========================================
-- 1) 기존 고아 개인정보 정리
-- ========================================
DELETE FROM user_devices WHERE user_id NOT IN (SELECT id FROM users);
-- HabitService.closeRequest와 동일하게 START는 양측, EXTEND는 요청자의 열린 상태만 BROKEN 처리한다.
-- 만료 시각이 지난 REQUESTED도 같은 종료 규칙을 적용하며, EXTEND 수신자의 기존 사이클은 유지한다.
-- 요청 FK cascade 전에 정상 사용자의 대기를 정리하고 삭제될 상대와의 연결을 해제한다.
WITH orphan_pair_peers AS (
    SELECT peer.id,
           BOOL_OR(request.kind = 'START' OR peer.id = request.requester_tracker_id) AS should_break
    FROM habit_pair_requests request
    JOIN habit_trackers orphan
        ON orphan.id = request.requester_tracker_id OR orphan.id = request.partner_tracker_id
    JOIN habit_trackers peer
        ON (orphan.id = request.requester_tracker_id AND peer.id = request.partner_tracker_id)
        OR (orphan.id = request.partner_tracker_id AND peer.id = request.requester_tracker_id)
    WHERE request.status = 'REQUESTED'
      AND NOT EXISTS (SELECT 1 FROM users WHERE id = orphan.user_id)
      AND EXISTS (SELECT 1 FROM users WHERE id = peer.user_id)
    GROUP BY peer.id
)
UPDATE habit_trackers peer
SET status = CASE
        WHEN pair.should_break AND peer.status IN ('PENDING', 'ACTIVE', 'COMPLETED_UNCLAIMED') THEN 'BROKEN'
        ELSE peer.status
    END,
    partner_tracker_id = NULL,
    friend_link_id = NULL,
    version = peer.version + 1
FROM orphan_pair_peers pair
WHERE peer.id = pair.id;

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
