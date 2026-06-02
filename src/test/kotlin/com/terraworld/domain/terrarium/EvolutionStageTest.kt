package com.terraworld.domain.terrarium

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * EvolutionStage 진화 단계 해금 임계값 contract. N4(구현 계획서 v4) 합의 —
 * unlockLevel 을 level_configs seed 범위(1~10) 안으로 조정해 영구 도달불가(WORLD20/CUSTOM30) 해소.
 * 프론트 useEvolution 의 unlockLevel 과 동기화돼야 하는 SoT.
 */
class EvolutionStageTest {
    @Test
    fun `unlockLevel 은 N4 합의값(POT1 BOTTLE2 PALUDARIUM5 WORLD8 CUSTOM10)`() {
        assertEquals(1, EvolutionStage.POT.unlockLevel)
        assertEquals(2, EvolutionStage.BOTTLE.unlockLevel)
        assertEquals(5, EvolutionStage.PALUDARIUM.unlockLevel)
        assertEquals(8, EvolutionStage.WORLD.unlockLevel)
        assertEquals(10, EvolutionStage.CUSTOM.unlockLevel)
    }

    @Test
    fun `모든 unlockLevel 은 1부터 10 범위 안이라 도달 가능`() {
        EvolutionStage.entries.forEach {
            assertTrue(it.unlockLevel in 1..10, "${it.name} unlockLevel=${it.unlockLevel} 가 1..10 밖")
        }
    }

    @Test
    fun `unlockedFor level1 은 POT 만`() {
        assertEquals(listOf(EvolutionStage.POT), EvolutionStage.unlockedFor(1))
    }

    @Test
    fun `unlockedFor 임계 경계 동작`() {
        assertEquals(setOf(EvolutionStage.POT, EvolutionStage.BOTTLE), EvolutionStage.unlockedFor(2).toSet())
        assertEquals(setOf(EvolutionStage.POT, EvolutionStage.BOTTLE), EvolutionStage.unlockedFor(4).toSet())
        assertTrue(EvolutionStage.PALUDARIUM in EvolutionStage.unlockedFor(5))
        assertFalse(EvolutionStage.WORLD in EvolutionStage.unlockedFor(7))
        assertTrue(EvolutionStage.WORLD in EvolutionStage.unlockedFor(8))
        assertFalse(EvolutionStage.CUSTOM in EvolutionStage.unlockedFor(9))
    }

    @Test
    fun `unlockedFor level10 은 전 단계 5개`() {
        assertEquals(EvolutionStage.entries.toSet(), EvolutionStage.unlockedFor(10).toSet())
    }

    @Test
    fun `level 0 이하면 아무 단계도 해금 안 됨`() {
        assertTrue(EvolutionStage.unlockedFor(0).isEmpty())
    }
}
