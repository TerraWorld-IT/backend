-- V27: 육성(정령/판타지식물) substrate (낙서장 P3). 연속 기록 스트릭으로 stage 진화 + 키우기 실패(동면).
-- additive. 커스텀 P3 확장은 growth_species / growth_stages row 추가로 흡수.

CREATE TABLE growth_species (
    code       VARCHAR(32) PRIMARY KEY,
    kind       VARCHAR(16) NOT NULL,   -- SPIRIT | PLANT
    name_ko    VARCHAR(32) NOT NULL,
    sort_order INT         NOT NULL
);
INSERT INTO growth_species (code, kind, name_ko, sort_order) VALUES
    ('cat',         'SPIRIT', '고양이 정령', 1),
    ('tomato-vine', 'PLANT',  '토마토 덩굴', 2);

-- stage 임계값 (kind별 3단계 = 1/15/30 연속, 확장 가능)
CREATE TABLE growth_stages (
    kind        VARCHAR(16) NOT NULL,   -- SPIRIT | PLANT
    stage_order INT         NOT NULL,   -- 1,2,3
    threshold   INT         NOT NULL,   -- 연속 기록 임계
    label_ko    VARCHAR(24) NOT NULL,
    PRIMARY KEY (kind, stage_order)
);
INSERT INTO growth_stages (kind, stage_order, threshold, label_ko) VALUES
    ('SPIRIT', 1, 1,  '알'),   ('SPIRIT', 2, 15, '깨진 알'), ('SPIRIT', 3, 30, '정령'),
    ('PLANT',  1, 1,  '씨앗'), ('PLANT',  2, 15, '새싹'),    ('PLANT',  3, 30, '식물');

-- 유저 육성 개체
CREATE TABLE growth_instances (
    id                    BIGSERIAL   PRIMARY KEY,
    user_id               TEXT        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    species_code          VARCHAR(32) NOT NULL REFERENCES growth_species(code),
    natural_streak        INT         NOT NULL DEFAULT 0,   -- 자연 연속(실패 시 리셋)
    sparkle_bought_count  INT         NOT NULL DEFAULT 0,   -- 반짝이 구매분(실패해도 유지, 정책 §3-8)
    current_stage         INT         NOT NULL DEFAULT 1,
    last_progress_at      TIMESTAMP,                        -- dormancy 판정 기준(P0.5 이식)
    dormant_at            TIMESTAMP,                        -- 키우기 실패(동면) 진입 시각
    dormancy_recovered_at TIMESTAMP,                        -- 복구(기록재개/광고) 시각
    version               BIGINT      NOT NULL DEFAULT 0,   -- 낙관적 락
    CONSTRAINT ux_growth_instance UNIQUE (user_id, species_code)
);
CREATE INDEX ix_growth_instance_user ON growth_instances (user_id);
