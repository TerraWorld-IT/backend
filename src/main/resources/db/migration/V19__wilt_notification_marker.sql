-- Codex audit Q4 (구현 계획서 v4, 2026-05-21): 시들기 알림 마커.
--
-- 변경 사유: WiltScheduler 가 `days == 3/7/14` 정각 일치로 event publish 하면, 자정 KST 에
-- 앱이 다운돼 있으면 해당 임계값 알림이 영구 누락. 마커 기반 `days >= threshold && 마커 없음`
-- 조건으로 전환 — 다운 후 복구 시 누락분 일괄 발송 + 단계당 1회만 (중복 없음).
--
-- 사용자가 다시 기록하면 (days == 0) 마커 전체 삭제 → 다음 시들기 시 재알림.

CREATE TABLE IF NOT EXISTS wilt_notification_marker (
    id           BIGSERIAL    PRIMARY KEY,
    user_id      VARCHAR(255) NOT NULL,
    marker_type  VARCHAR(32)  NOT NULL,
    notified_at  TIMESTAMP    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_wilt_marker UNIQUE (user_id, marker_type),
    CONSTRAINT fk_wilt_marker_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_wilt_marker_user ON wilt_notification_marker(user_id);

COMMENT ON TABLE wilt_notification_marker IS
    'Q4: 시들기/출석 알림 발송 마커. marker_type = WILT_1 / WILT_2 / WILT_3 / ATTENDANCE_1';
