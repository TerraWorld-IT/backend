package com.terraworld.api.user

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.level.LevelConfig
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.lenient
import org.mockito.Mockito.mock
import java.util.Optional

/**
 * UserLevelService 레벨 자동 갱신 — totalExp 가 만족하는 가장 높은 level 로 점프 + delta 반환.
 * 곡선은 V14 선형 N×100 (level1=0, level2=100, ... level10=900) 기준으로 seed.
 * (EXP→레벨 분기 = 진화/보상 연쇄의 기반 — 회귀 가드. 진화 unlock 임계는 EvolutionStageTest.)
 */
class UserLevelServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var levelRepo: FakeLevelConfigRepository
    private lateinit var service: UserLevelService

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        levelRepo = FakeLevelConfigRepository()
        val terrariumRepo = mock(TerrariumRepository::class.java)
        lenient().`when`(terrariumRepo.findByUserId(anyString())).thenReturn(Optional.empty())
        service = UserLevelService(userRepo, levelRepo, terrariumRepo, mock(AuditService::class.java))
        for (lvl in 1..10) {
            levelRepo.save(LevelConfig(level = lvl, requiredExp = ((lvl - 1) * 100).toLong()))
        }
    }

    private fun user(
        totalExp: Long,
        level: Int,
    ): User =
        User(id = "user-1", nickname = "테스터").apply {
            this.totalExp = totalExp
            this.level = level
        }

    @Test
    fun `totalExp 0 level1 — 변동 없음`() {
        val u = user(totalExp = 0, level = 1)
        assertEquals(0, service.checkAndApplyLevelUp(u))
        assertEquals(1, u.level)
    }

    @Test
    fun `100 EXP — level 2 로 delta 1`() {
        val u = user(totalExp = 100, level = 1)
        assertEquals(1, service.checkAndApplyLevelUp(u))
        assertEquals(2, u.level)
    }

    @Test
    fun `550 EXP — level 6 로 delta 5`() {
        val u = user(totalExp = 550, level = 1)
        assertEquals(5, service.checkAndApplyLevelUp(u))
        assertEquals(6, u.level)
    }

    @Test
    fun `이미 목표 레벨이면 no-op`() {
        val u = user(totalExp = 100, level = 2)
        assertEquals(0, service.checkAndApplyLevelUp(u))
        assertEquals(2, u.level)
    }

    @Test
    fun `900 EXP — 만렙 10 으로 delta 9`() {
        val u = user(totalExp = 900, level = 1)
        assertEquals(9, service.checkAndApplyLevelUp(u))
        assertEquals(10, u.level)
    }

    @Test
    fun `level_config 비어있으면 0 (seed 미적용)`() {
        levelRepo.deleteAll()
        val u = user(totalExp = 500, level = 1)
        assertEquals(0, service.checkAndApplyLevelUp(u))
        assertEquals(1, u.level)
    }

    private class FakeUserRepository :
        FakeJpaRepository<User, String>(),
        UserRepository {
        override fun extractId(entity: User): String = entity.id
    }

    private class FakeLevelConfigRepository :
        FakeJpaRepository<LevelConfig, Int>(),
        LevelConfigRepository {
        override fun extractId(entity: LevelConfig): Int = entity.level

        override fun findByLevel(level: Int): Optional<LevelConfig> = Optional.ofNullable(store[level])
    }
}
