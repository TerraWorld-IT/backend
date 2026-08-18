-- V38 (apjek social loop, 2026-08-18): 인앱 알림함 + 습관 응원 + 투두 루틴 기반 테이블 3종.
--
-- 배경: 푸시(FCM)는 발송 즉시 휘발되어 앱 내에서 다시 볼 수 없다. 인앱 알림함(user_notifications)이
-- 그 영속 저장 경로 — 푸시 발송(FcmEventListener)과 **별개의 저장 경로**로 병행한다.
-- 습관 응원(habit_cheers)은 친구 연동 습관 트래커에 하루 3회까지 응원 메시지를 보내는 기능의
-- 발송 원장(rate-limit SoT), 투두 루틴(todo_routines)은 반복 투두 정의 CRUD 의 저장소다.

-- ========================================
-- 1) user_notifications — 인앱 알림함
-- ========================================
-- id 는 UUID (NotificationApi 확정안의 id 가 string — Hibernate 가 클라이언트 측 생성,
-- DEFAULT 는 수동 INSERT 보조용). 읽음 처리는 read_at 타임스탬프 (NULL = 미읽음).
CREATE TABLE IF NOT EXISTS user_notifications (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    TEXT         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type       VARCHAR(32)  NOT NULL CHECK (type IN ('SPIRIT_ARRIVED', 'PAYMENT', 'FRIEND_JOINED', 'CHEER', 'SYSTEM')),
    title      VARCHAR(100) NOT NULL,
    body       VARCHAR(500) NOT NULL,
    route      VARCHAR(200),
    created_at TIMESTAMP    NOT NULL DEFAULT NOW(),
    read_at    TIMESTAMP
);

-- 알림함 목록 = user_id 기준 최신순 페이지 조회 — (user_id, created_at DESC) 복합 인덱스.
CREATE INDEX IF NOT EXISTS idx_user_notifications_user_created ON user_notifications (user_id, created_at DESC);

COMMENT ON TABLE user_notifications IS
    'V38: 인앱 알림함. 푸시(FCM)와 별개의 영속 저장 경로 — 커밋 확정 후 @Async 리스너가 append';
COMMENT ON COLUMN user_notifications.route IS
    '알림 탭 시 이동할 클라이언트 경로 (예: /record, /friends) — 없으면 NULL';
COMMENT ON COLUMN user_notifications.read_at IS
    '읽음 처리 시각 — NULL 이면 미읽음 (미읽음 카운트 판정 기준)';

-- ========================================
-- 2) habit_cheers — 습관 응원 발송 원장
-- ========================================
-- 일 3회/트래커 제한의 SoT: user_notifications 는 발신자·트래커 컬럼이 없고 append 가
-- 커밋 후 비동기(@Async AFTER_COMMIT)라 카운트 판정에 race 가 있어, 최소 테이블로 분리한다.
-- cheered_date 는 KST 기준 날짜 (KstTime.today()) — 타임스탬프 범위 연산 없이 일 단위 카운트.
CREATE TABLE IF NOT EXISTS habit_cheers (
    id           BIGSERIAL    PRIMARY KEY,
    tracker_id   BIGINT       NOT NULL REFERENCES habit_trackers(id) ON DELETE CASCADE,
    from_user_id TEXT         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    to_user_id   TEXT         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    message      VARCHAR(100) NOT NULL,
    cheered_date DATE         NOT NULL,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

-- rate-limit 카운트 경로: (발신자, 트래커, KST 날짜)
CREATE INDEX IF NOT EXISTS idx_habit_cheers_rate ON habit_cheers (from_user_id, tracker_id, cheered_date);

COMMENT ON TABLE habit_cheers IS
    'V38: 습관 응원 발송 원장 — 일 3회/트래커 rate-limit 판정 SoT (알림함과 별도, 동기 커밋)';
COMMENT ON COLUMN habit_cheers.cheered_date IS
    'KST 기준 발송 날짜 (KstTime.today()) — 일 단위 카운트 키';

-- ========================================
-- 3) todo_routines — 투두 루틴 정의
-- ========================================
-- 오늘 구체화(실제 투두 인스턴스 생성)는 클라이언트 책임 — 서버는 정의 CRUD 만.
-- id 는 BIGSERIAL — TodoRoutineApi 확정안의 routineId 가 int64 (알림함 UUID 와 달리 순번 키).
-- days_of_week 는 비트마스크 저장 (bit0=일 ... bit6=토, 0~127) — 배열 타입 미사용(기존 스키마에
-- 배열 선례 없음). API 경계(Set<Int>, spec 규약 0=일..6=토)와의 변환은 도메인 companion 이 담당.
-- soft delete 는 is_deleted 플래그 (activity_records.is_deleted 관례).
CREATE TABLE IF NOT EXISTS todo_routines (
    id           BIGSERIAL   PRIMARY KEY,
    user_id      TEXT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    label        VARCHAR(50) NOT NULL,
    repeat_type  VARCHAR(16) NOT NULL CHECK (repeat_type IN ('DAILY', 'WEEKLY')),
    days_of_week INT         NOT NULL DEFAULT 0 CHECK (days_of_week BETWEEN 0 AND 127),
    is_deleted   BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMP   NOT NULL DEFAULT NOW()
);

-- 목록/활성 카운트 경로: (user_id, is_deleted)
CREATE INDEX IF NOT EXISTS idx_todo_routines_user ON todo_routines (user_id, is_deleted);

COMMENT ON TABLE todo_routines IS
    'V38: 반복 투두 루틴 정의 (사용자당 활성 20개 제한 — 서비스 계층 advisory lock + 카운트로 강제)';
COMMENT ON COLUMN todo_routines.days_of_week IS
    'WEEKLY 반복 요일 비트마스크: bit0=일 ... bit6=토 (0~127). DAILY 는 0. API 경계는 요일 Set(0=일..6=토)';
