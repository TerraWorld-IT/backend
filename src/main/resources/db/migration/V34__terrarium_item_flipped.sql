-- 낙서장 자유배치 편집 영속 (req3 #2): 좌우 반전 상태.
-- scale / z_index 컬럼은 이미 존재(V1/V13) — flipped 만 신규 추가.
ALTER TABLE terrarium_items
    ADD COLUMN IF NOT EXISTS flipped BOOLEAN NOT NULL DEFAULT FALSE;
