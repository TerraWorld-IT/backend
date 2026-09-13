-- COIN -> 활동토큰 추가. 기존 pair/rate/cap 은 유지한다.
-- 왕복 상한: 5 * 0.1 * 0.9 * 0.9 = 0.405 < 1 (각 단계 floor 는 더 감소시킨다).
-- 배포 DB 에서 역방향이 조정됐으면 실제 exchange_rates 값으로 안전성을 확인한다.
DO $$
BEGIN
    IF (SELECT COUNT(*) FROM exchange_rates
        WHERE from_code IN ('DEW', 'SUN', 'BOLT', 'WIND') AND to_code = 'COIN') <> 4
        OR EXISTS (
            SELECT 1 FROM exchange_rates
            WHERE from_code IN ('DEW', 'SUN', 'BOLT', 'WIND') AND to_code = 'COIN'
              AND 5.0 * rate * 0.9 * (1 - fee_bps / 10000.0) >= 1
        ) THEN
        RAISE EXCEPTION 'COIN to activity seed requires four reverse pairs with round-trip < 1';
    END IF;
END $$;

INSERT INTO exchange_rates (from_code, to_code, rate, fee_bps, daily_cap) VALUES
    ('COIN', 'DEW',  5.000000, 1000, 100),
    ('COIN', 'SUN',  5.000000, 1000, 100),
    ('COIN', 'BOLT', 5.000000, 1000, 100),
    ('COIN', 'WIND', 5.000000, 1000, 100);
