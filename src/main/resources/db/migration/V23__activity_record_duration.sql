-- V23: activity record duration(분) 영속화 — silent-drop 실버그 fix (P0-1).
--
-- 배경: CreateRecordRequest.duration 은 spec→controller→service 까지 흐르나
--       activity_records 에 컬럼이 없어 RecordService.toResponse() 가 duration=null
--       하드코딩 → create 응답만 echo 로 정상처럼 보이고 목록 reload 시 소실.
--
-- 추가 컬럼:
--   public.activity_records.duration_minutes — 활동 소요 시간(분). nullable.

ALTER TABLE public.activity_records
    ADD COLUMN IF NOT EXISTS duration_minutes INT;

COMMENT ON COLUMN public.activity_records.duration_minutes
    IS 'POST /records 의 duration(분). null 허용(미입력 기록).';
