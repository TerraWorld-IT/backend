-- 2026-07-15 성능 감사 quick-win 인덱스 3종 (BE-06 / BE-07).
--
-- 주의 (2026-07-15 적대적 리뷰 보류 항목): 일반 CREATE INDEX 는 대상 테이블 쓰기를 잠그지만,
-- 현 데이터 규모(출시 전)에서는 ms 단위라 그대로 둔다. 데이터 성장 후 신규 인덱스를 추가할
-- 때는 CREATE INDEX CONCURRENTLY + Flyway `executeInTransaction=false` (비트랜잭션) 별도
-- 마이그레이션으로 작성할 것 — CONCURRENTLY 는 트랜잭션 블록 안에서 실행 불가.

-- BE-06 (1): 월간 engagement 랭킹(RecordRepository.findEngagementRanking / countByUserAndPeriod)이
-- recorded_date 범위 + is_deleted=false 조건인데 기존 idx_records_user_date 는 user_id 선행이라
-- 날짜 단독 범위 스캔에 쓰이지 못함 → 전 테이블 스캔. 부분 인덱스로 범위 스캔 전환.
CREATE INDEX IF NOT EXISTS idx_records_recorded_date
    ON activity_records (recorded_date)
    WHERE is_deleted = FALSE;

-- BE-06 (2): 월간 배치 랭킹(TerrariumPlacementHistoryRepository — placed_at BETWEEN 기간 조회)용.
-- 기존 idx_terrarium_placement_history_user_month 는 user_id 선행이라 기간 단독 조회 미커버.
CREATE INDEX IF NOT EXISTS idx_placement_history_placed_at
    ON terrarium_placement_history (placed_at);

-- BE-07: 친구관계 검증(InviteRepository.existsAcceptedBetween 등)의 OR 절이
-- inviter_user_id 인덱스(idx_invites_inviter)만 타고 invitee_user_id 쪽은 seq scan.
-- 미수락 invite(invitee NULL) 를 제외한 부분 인덱스 추가.
CREATE INDEX IF NOT EXISTS idx_invites_invitee
    ON invites (invitee_user_id)
    WHERE invitee_user_id IS NOT NULL;
