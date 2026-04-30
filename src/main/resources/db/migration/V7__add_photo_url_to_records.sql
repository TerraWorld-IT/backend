-- V7: Phase 3 scope — activity record 사진 첨부 + decoration 랭킹 prep.
--
-- 추가 컬럼:
--   public.activity_records.photo_url — 첨부 사진의 공개 URL (nullable)
--
-- 추가 테이블 (decoration 랭킹용):
--   public.terrarium_placement_history — 배치 시점 기록. 월간 신규 배치 카운트 산정.

-- ─── 사진 첨부 ──────────────────────────────────────────────
ALTER TABLE public.activity_records
    ADD COLUMN IF NOT EXISTS photo_url VARCHAR(2048);

COMMENT ON COLUMN public.activity_records.photo_url
    IS 'POST /uploads/photo 로 업로드한 사진 URL. CDN 또는 dataUrl(PoC).';

-- ─── 배치 이력 ──────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS public.terrarium_placement_history (
    id          BIGSERIAL PRIMARY KEY,
    user_id     TEXT        NOT NULL,
    item_id     BIGINT      NOT NULL,
    slot_id     INT         NOT NULL,
    placed_at   TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT terrarium_placement_history_user_fk
        FOREIGN KEY (user_id) REFERENCES public.users (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_terrarium_placement_history_user_month
    ON public.terrarium_placement_history (user_id, placed_at);

COMMENT ON TABLE public.terrarium_placement_history
    IS '테라리움 신규 배치 이력. 월간 decoration 랭킹 산정.';
