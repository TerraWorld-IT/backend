package com.terraworld.api.user

import com.terraworld.common.audit.AuditService
import com.terraworld.domain.level.LevelConfig
import com.terraworld.domain.level.LevelConfigRepository
import com.terraworld.domain.terrarium.EvolutionStage
import com.terraworld.domain.terrarium.Terrarium
import com.terraworld.domain.terrarium.TerrariumBackground
import com.terraworld.domain.terrarium.TerrariumRepository
import com.terraworld.domain.user.User
import com.terraworld.domain.user.UserRepository
import com.terraworld.test.FakeJpaRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Optional
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * UserLevelService.checkAndApplyLevelUp 분기 커버.
 *
 * 곡선: level_configs seed (선형 N×100 가정). lvl1=0, lvl2=100, lvl3=200, lvl4=300, lvl5=400.
 * EvolutionStage unlockLevel: POT(1), BOTTLE(2), PALUDARIUM(5), WORLD(8), CUSTOM(10).
 *
 * - 해피: totalExp 가 더 높은 level 만족 → level 갱신 + delta 반환
 * - no-op: totalExp 가 현재 level 임계 미달 → 0 반환, level 불변
 * - config 빈 경우: 0 반환 (V12 seed 미적용 방어)
 * - evolution unlock: 새 stage 임계 통과 시 terrarium.evolutionUnlockedAt 갱신
 * - evolution no-change: 새 stage 미통과 시 terrarium 미변경
 */
class UserLevelServiceTest {
    private lateinit var userRepo: FakeUserRepository
    private lateinit var levelConfigRepo: FakeLevelConfigRepository
    private lateinit var terrariumRepo: FakeTerrariumRepository
    private lateinit var auditService: AuditService
    private lateinit var service: UserLevelService

    private lateinit var background: TerrariumBackground

    @BeforeEach
    fun setup() {
        userRepo = FakeUserRepository()
        levelConfigRepo = FakeLevelConfigRepository()
        terrariumRepo = FakeTerrariumRepository()
        auditService = org.mockito.Mockito.mock(AuditService::class.java)
        service = UserLevelService(userRepo, levelConfigRepo, terrariumRepo, auditService)

        // 선형 N×100 seed (level 1~5 만 — 본 test 범위)
        levelConfigRepo.save(LevelConfig(level = 1, requiredExp = 0))
        levelConfigRepo.save(LevelConfig(level = 2, requiredExp = 100))
        levelConfigRepo.save(LevelConfig(level = 3, requiredExp = 200))
        levelConfigRepo.save(LevelConfig(level = 4, requiredExp = 300))
        levelConfigRepo.save(LevelConfig(level = 5, requiredExp = 400))

        background = TerrariumBackground(id = 1L, name = "기본", assetUrl = "https://cdn/bg.png")
    }

    private fun newUser(
        id: String = "user-1",
        level: Int = 1,
        totalExp: Long = 0,
    ): User {
        val u = User(id = id, nickname = "테스터", level = level, totalExp = totalExp)
        userRepo.save(u)
        return u
    }

    @Test
    fun `해피 패스 — totalExp 300 이면 level 1 에서 4 로 올라 delta 3 반환`() {
        val user = newUser(level = 1, totalExp = 300)

        val delta = service.checkAndApplyLevelUp(user)

        assertEquals(3, delta)
        assertEquals(4, userRepo.findById("user-1").get().level)
    }

    @Test
    fun `no-op — totalExp 가 다음 임계 미달이면 0 반환하고 level 불변`() {
        // level 1, totalExp 99 → lvl2(100) 미달 → 변동 없음
        val user = newUser(level = 1, totalExp = 99)

        val delta = service.checkAndApplyLevelUp(user)

        assertEquals(0, delta)
        assertEquals(1, userRepo.findById("user-1").get().level)
    }

    @Test
    fun `config 가 비어있으면 0 반환하고 level 불변`() {
        levelConfigRepo.deleteAll()
        val user = newUser(level = 1, totalExp = 999)

        val delta = service.checkAndApplyLevelUp(user)

        assertEquals(0, delta)
        assertEquals(1, userRepo.findById("user-1").get().level)
    }

    @Test
    fun `evolution unlock — level 1 에서 5 로 오르면 PALUDARIUM 신규 해금되어 evolutionUnlockedAt 갱신`() {
        // level 1 unlocked = [POT] / level 5 unlocked = [POT, BOTTLE, PALUDARIUM] → 신규 2개
        val user = newUser(level = 1, totalExp = 400)
        val terrarium =
            Terrarium(
                id = 10L,
                user = user,
                background = background,
                evolutionStage = EvolutionStage.POT,
            )
        terrariumRepo.save(terrarium)

        val delta = service.checkAndApplyLevelUp(user)

        assertEquals(4, delta)
        assertEquals(5, userRepo.findById("user-1").get().level)
        // sanity: 새 stage 가 실제로 늘어났는지 확인
        assertEquals(1, EvolutionStage.unlockedFor(1).size)
        assertEquals(3, EvolutionStage.unlockedFor(5).size)
        assertNotNull(terrariumRepo.findByUserId("user-1").get().evolutionUnlockedAt)
    }

    @Test
    fun `evolution no-change — level 3 에서 4 로 올라도 새 stage 없으면 evolutionUnlockedAt 미갱신`() {
        // level 3 unlocked = [POT, BOTTLE] / level 4 unlocked = [POT, BOTTLE] → 변동 없음
        val user = newUser(level = 3, totalExp = 300)
        val terrarium =
            Terrarium(
                id = 11L,
                user = user,
                background = background,
                evolutionStage = EvolutionStage.BOTTLE,
            )
        terrariumRepo.save(terrarium)

        val delta = service.checkAndApplyLevelUp(user)

        assertEquals(1, delta)
        assertEquals(4, userRepo.findById("user-1").get().level)
        assertEquals(2, EvolutionStage.unlockedFor(3).size)
        assertEquals(2, EvolutionStage.unlockedFor(4).size)
        assertNull(terrariumRepo.findByUserId("user-1").get().evolutionUnlockedAt)
    }

    // ─── Fakes ─────────────────────────────────────────────────

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

    private class FakeTerrariumRepository :
        FakeJpaRepository<Terrarium, Long>(),
        TerrariumRepository {
        override fun extractId(entity: Terrarium): Long = entity.id

        override fun findByUserId(userId: String): Optional<Terrarium> = Optional.ofNullable(store.values.firstOrNull { it.user.id == userId })
    }
}
