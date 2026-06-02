package com.terraworld.api.terrarium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * 자유배치 좌표 ratio(0~1) ↔ permille store(0~10000) 변환 정밀도.
 * API/도메인은 0~1 비율, 저장은 free_x/y_pixel(Int) 컬럼을 permille 재사용 — 무손실·해상도 독립.
 * (CLAUDE.md 좌표/정밀도 디시플린 — round-trip 손실 0 보장.)
 */
class PlacementServiceConversionTest {
    @Test
    fun `0_01 그리드 라운드트립 무손실`() {
        for (i in 0..100) {
            val ratio = i / 100.0
            val back = PlacementService.storeToRatio(PlacementService.ratioToStore(ratio))
            assertEquals(ratio, back, 1e-9, "ratio=$ratio 라운드트립 손실")
        }
    }

    @Test
    fun `경계값 변환`() {
        assertEquals(0, PlacementService.ratioToStore(0.0))
        assertEquals(10_000, PlacementService.ratioToStore(1.0))
        assertEquals(5_000, PlacementService.ratioToStore(0.5))
    }

    @Test
    fun `범위 밖 입력은 0_1 로 clamp`() {
        assertEquals(0, PlacementService.ratioToStore(-0.3))
        assertEquals(10_000, PlacementService.ratioToStore(1.7))
    }

    @Test
    fun `null store 는 0_0 비율`() {
        assertEquals(0.0, PlacementService.storeToRatio(null))
    }
}
