-- 낙서장 P1 finale: 화폐 read-cutover(WalletBuilder→user_currency_balances) 완료 후 구 저장 드롭.
-- 신 substrate(user_currency_balances)가 지갑 SoT. income/spend/exchange/tier/growth/habit 모두
-- CurrencyService 경유. 구 컬럼/테이블은 dead → 제거 (파괴적, prototype).
--
-- 의도적 no-backfill (Codex HIGH#1 검토 결론): prototype·prod 데이터 없음 + canonical 경로는
-- 빈 DB 에 V1→V32 순차 실행(데이터 손실 대상 없음). 리팩토링 슬라이스(V25~)에서 화폐 substrate 도입 후
-- 모든 income/spend 가 신 substrate 로 기록되어 왔으므로 구 컬럼→신 substrate backfill 은
-- 오히려 이중 계상(double-count) 위험이 있어 수행하지 않는다. 실 운영 데이터 이관이 필요해지면
-- 별도 마이그레이션에서 "구 컬럼 값 vs 신 substrate 잔액" 대사 후 조건부 이관할 것.

-- 카테고리 토큰 (신 substrate DEW/SUN/BOLT/WIND 로 대체)
DROP TABLE IF EXISTS user_tokens;

-- users 구 화폐 컬럼 (신 substrate COIN/RUBY 로 대체)
ALTER TABLE users DROP COLUMN IF EXISTS basic_coin;
ALTER TABLE users DROP COLUMN IF EXISTS special_coin;
