-- /analyze 2026-05-18 발견 (Codex C-2 HIGH): OpenAPI spec
-- (`paths/terrarium-placements.yaml`) 가 "같은 slotId 중복 배치 불가" 를 명시하지만
-- V3/V4 schema 의 `slot_id INTEGER` 는 UNIQUE 제약 없음.
-- TerrariumService.updatePlacements 도 중복 검사 없음 — race condition 잠재.
--
-- 본 V15 의 역할:
--   terrarium_items 에 (terrarium_id, slot_id) partial UNIQUE 인덱스 추가.
--   PARTIAL: free placement 모드 (slot_id IS NULL) 는 제약 외 (자유배치는 슬롯 개념 X).
--
-- 영향 (Phase 4 pre-launch — production 사용자 0건):
--   - 신규 인덱스 생성 cost 무시 가능 (테이블 행 적음)
--   - 기존 데이터 중복 시 인덱스 생성 실패 → migration 실패로 fail-fast
--   - 운영 단계에서 동일 slot 에 2번 INSERT 시도 시 23505 (unique_violation) → BusinessException 으로 mapping 권장 (별 cycle: ErrorCode.TERRARIUM_SLOT_TAKEN 추가)
--
-- 검증:
--   - INSERT 충돌 시 trace 가 명시 — service 가 retry 안 함 (frontend 가 placement state 재로드 후 재시도)
--   - free placement (slot_id IS NULL) 는 무제한 — `(terrarium_id, slot_id)` UNIQUE 가 NULL 을 unique 로 취급하지 않음 (PostgreSQL 기본 동작 + partial WHERE 로 명시)
--
-- 관련 Codex finding (V15 미포함, deferred):
--   - C-6 ad_watch_logs daily UNIQUE: `watched_at` TIMESTAMP 컬럼만 있어 daily UNIQUE 불가능.
--     `(user_id, DATE(watched_at))` partial UNIQUE 도 매일 5회 시청 의도와 충돌. fix 는
--     별 cycle 의 design 결정 (daily_ad_counts 별 테이블 + row-level lock + ON CONFLICT
--     DO UPDATE / pessimistic lock). Phase 4 pre-launch 단계라 race window 0.

-- Codex code-review CDX-002 (2026-05-18): 운영 데이터에 (terrarium_id, slot_id) 중복 row
-- 존재 시 CREATE UNIQUE INDEX 가 silent fail 또는 abort. Phase 4 pre-launch 라 현재
-- production user 0 이지만 fail-fast pre-check 안전망. 중복 검출 시 명시 메시지로 RAISE.
DO $$
DECLARE
    duplicate_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO duplicate_count
    FROM (
        SELECT terrarium_id, slot_id
        FROM public.terrarium_items
        WHERE slot_id IS NOT NULL
        GROUP BY terrarium_id, slot_id
        HAVING COUNT(*) > 1
    ) dupes;

    IF duplicate_count > 0 THEN
        RAISE EXCEPTION 'V15 abort: % duplicate (terrarium_id, slot_id) row(s) detected. De-dup before re-running migration.', duplicate_count;
    END IF;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_terrarium_items_terrarium_slot
    ON public.terrarium_items (terrarium_id, slot_id)
    WHERE slot_id IS NOT NULL;

COMMENT ON INDEX public.ux_terrarium_items_terrarium_slot
    IS 'OpenAPI spec terrarium-placements.yaml "같은 slotId 중복 배치 불가" 강제. free placement (slot_id IS NULL) 는 제외.';
