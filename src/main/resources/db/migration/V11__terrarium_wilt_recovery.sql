-- UltraPlan v3 P-WILT-001 + J-WILT-001 (2026-05-18)
-- 시들기 광고 복구 흐름: AD 시청 시 wilting state reset
--
-- 변경 사유: RewardService.claimAdReward 가 햇살 (specialCoin) 만 +1 지급하고
-- wilting state reset 안 함 (Codex pre-audit J-WILT-001). 사용자가 광고를
-- 보고 식물을 복구해도 다음 진입 시 다시 시들어 보이는 회귀 발생.
--
-- 해결: terrariums 테이블에 wilt_recovered_at 컬럼 추가.
-- TerrariumService.computeWiltingState 가 max(record.recordedDate, terrarium.wiltRecoveredAt)
-- 기준으로 days 계산. ActivityRecord 더미 row 오염 없이 광고 복구 흔적 추적.
--
-- 권장 default (UltraPlan v3 §4.2.A): 시들기 단계 3/7/14 일 + 햇살 +1 만 보상
-- (이슬 보너스 X, 이중 보상 회피). 본 컬럼은 NULL 허용 (광고 복구 안 한 사용자 = 기존 동작).

ALTER TABLE terrariums
    ADD COLUMN wilt_recovered_at TIMESTAMP NULL;

COMMENT ON COLUMN terrariums.wilt_recovered_at IS
    'P-WILT-001: 마지막 광고 시청 복구 시각. computeWiltingState 가 max(last_record_date, this) 사용. NULL = 광고 복구 안 함';
