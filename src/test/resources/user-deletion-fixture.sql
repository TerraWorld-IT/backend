-- 실제 최신 스키마의 삭제·보존 대상을 양쪽 사용자에 심는다.
INSERT INTO auth."user" (id) VALUES ('deleted-user'), ('keep-user');
INSERT INTO users (id, nickname) VALUES ('deleted-user', '탈퇴'), ('keep-user', '유지');
INSERT INTO categories (id, name, token_name, is_custom, owner_user_id)
VALUES (9001, '탈퇴 카테고리', '토큰', TRUE, 'deleted-user'), (9002, '유지 카테고리', '토큰', TRUE, 'keep-user');
INSERT INTO activity_records (user_id, category_id, recorded_date, photo_url, is_deleted, partner_user_id)
VALUES ('deleted-user', 9001, CURRENT_DATE, 'https://photos.example/photos/00000000-0000-0000-0000-000000000001.jpg', FALSE, 'keep-user'),
       ('deleted-user', 9001, CURRENT_DATE, 'https://photos.example/photos/00000000-0000-0000-0000-000000000001.jpg', FALSE, NULL),
       ('deleted-user', 9001, CURRENT_DATE, 'https://photos.example/photos/00000000-0000-0000-0000-000000000002.png', TRUE, NULL),
       ('deleted-user', 9001, CURRENT_DATE, 'https://external.example/image.png', FALSE, NULL),
       ('keep-user', 9002, CURRENT_DATE, NULL, FALSE, 'deleted-user');
INSERT INTO wallet_transactions (user_id, currency_type, amount, balance_after, reason)
SELECT id, 'BASIC', 1, 1, 'TEST' FROM users;
INSERT INTO user_items (user_id, item_id) SELECT id, (SELECT MIN(id) FROM items) FROM users;
INSERT INTO terrariums (id, user_id, background_id)
VALUES (9001, 'deleted-user', (SELECT MIN(id) FROM terrarium_backgrounds)),
       (9002, 'keep-user', (SELECT MIN(id) FROM terrarium_backgrounds));
INSERT INTO terrarium_items (terrarium_id, item_id, pos_x, pos_y)
SELECT id, (SELECT MIN(id) FROM items), 0, 0 FROM terrariums;
INSERT INTO terrarium_tier_backgrounds (terrarium_id, tier, background_id)
SELECT id, 'GLASS_JAR', background_id FROM terrariums;
INSERT INTO day_notes (user_id, note_date, note) SELECT id, CURRENT_DATE, '기록' FROM users;
INSERT INTO attendance_logs (user_id, check_in_date, streak, reward_basic_coins)
SELECT id, CURRENT_DATE, 1, 1 FROM users;
INSERT INTO user_entitlement (user_id, entitlement_key, tx_ref) SELECT id, 'free_placement', 'tx-' || id FROM users;
INSERT INTO entitlement_event (user_id, entitlement_key, action, reason, tx_ref)
SELECT id, 'free_placement', 'GRANT', 'PURCHASE', 'tx-' || id FROM users;
INSERT INTO wilt_notification_marker (user_id, marker_type) SELECT id, 'WILT_1' FROM users;
INSERT INTO user_currency_balances (user_id, currency_code, amount) SELECT id, 'COIN', 10 FROM users;
INSERT INTO user_grants (user_id, grant_type, grant_ref, idempotency_key, source)
SELECT id, 'CURRENCY', 'COIN', 'fixture', 'EVENT' FROM users;
INSERT INTO user_characters (user_id, character_code, acquired_via) SELECT id, 'cat', 'EVENT' FROM users;
INSERT INTO growth_instances (user_id, species_code) SELECT id, 'cat' FROM users;
INSERT INTO user_notifications (user_id, type, title, body) SELECT id, 'SYSTEM', '알림', '내용' FROM users;
INSERT INTO todo_routines (user_id, label, repeat_type) SELECT id, '루틴', 'DAILY' FROM users;
INSERT INTO ad_watch_logs (user_id) SELECT id FROM users;
INSERT INTO terrarium_placement_history (user_id, item_id, slot_id) SELECT id, (SELECT MIN(id) FROM items), 1 FROM users;
INSERT INTO user_devices (user_id, token, platform, is_active)
SELECT id, 'active-' || id, 'ANDROID', TRUE FROM users;
INSERT INTO user_devices (user_id, token, platform, is_active)
SELECT id, 'inactive-' || id, 'IOS', FALSE FROM users;
INSERT INTO habit_trackers (id, user_id, title, start_date, partner_tracker_id, current_cycle_id, current_streak_days)
VALUES (9001, 'deleted-user', '함께 습관', CURRENT_DATE, 9002, 9001, 3),
       (9002, 'keep-user', '함께 습관', CURRENT_DATE, 9001, 9002, 3);
INSERT INTO habit_cycles (id, tracker_id, user_id, cycle_no, started_on)
VALUES (9001, 9001, 'deleted-user', 1, CURRENT_DATE), (9002, 9002, 'keep-user', 1, CURRENT_DATE);
INSERT INTO habit_pair_requests (requester_tracker_id, requester_user_id, partner_tracker_id, partner_user_id, kind, status, expires_at)
VALUES (9001, 'deleted-user', 9002, 'keep-user', 'START', 'ACCEPTED', NOW() + INTERVAL '7 days'),
       (9002, 'keep-user', 9001, 'deleted-user', 'EXTEND', 'REQUESTED', NOW() + INTERVAL '7 days');
INSERT INTO habit_cheers (tracker_id, from_user_id, to_user_id, message, cheered_date)
VALUES (9001, 'keep-user', 'deleted-user', '응원', CURRENT_DATE), (9002, 'deleted-user', 'keep-user', '응원', CURRENT_DATE);
INSERT INTO ad_reward_nonce_inbox (nonce, user_id) SELECT 'nonce-' || id, id FROM users;
INSERT INTO exchange_daily_usage (user_id, from_code, to_code, usage_date, from_amount)
SELECT id, 'DEW', 'COIN', CURRENT_DATE, 10 FROM users;
INSERT INTO terrarium_like (liker_user_id, target_user_id)
VALUES ('deleted-user', 'keep-user'), ('keep-user', 'deleted-user');
INSERT INTO invites (code, inviter_user_id, invitee_user_id, expires_at)
VALUES ('deleted-invitation', 'deleted-user', 'keep-user', NOW() + INTERVAL '7 days'),
       ('retained-invitation', 'keep-user', 'deleted-user', NOW() + INTERVAL '7 days');
-- 아래 두 원장은 사용자 ID와 전체 행을 변경 없이 보존해야 한다.
INSERT INTO entitlement_tx_ledger (user_id, entitlement_key, tx_ref, action)
SELECT id, 'free_placement', 'tx-' || id, 'GRANT' FROM users;
INSERT INTO audit_logs (user_id, action, payload) SELECT id, 'TEST', '{"retained":true}'::jsonb FROM users;
