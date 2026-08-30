package com.terraworld.migration

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 파괴적 마이그레이션 런타임 검증 (낙서장 P1 finale).
 *
 * 318 유닛 테스트는 Fake repo 라 Flyway 를 실행하지 않는다 → V1→Vn SQL 이 실 Postgres 에서
 * 깨끗이 적용되는지 미검증. 본 스모크는 실 Postgres(testcontainers)에 전 마이그레이션을 순차 적용해
 * (1) 문법/순서 오류 없이 통과 (2) 파괴적 V32(구 지갑 컬럼/테이블 드롭)가 실제로 반영됨을 실증한다.
 *
 * Docker 부재 시 assumeTrue 로 skip (SKIP 사유: docker_unavailable) — CI/로컬 Docker 있으면 실행.
 */
class FlywayMigrationSmokeTest {
    @Test
    fun `V1부터 최신까지 실 Postgres 에 순차 적용 + V32 구 지갑 컬럼·테이블 드롭 실증`() {
        assumeTrue(dockerAvailable(), "Docker 미가용 — 마이그레이션 스모크 skip (docker_unavailable)")

        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()

            // 실 배포 순서 재현: better-auth 가 auth 스키마/user 테이블을 먼저 생성한 뒤 Flyway 가 돈다.
            // V5(auth_user_fk)가 auth."user" 를 요구하므로 최소 prerequisite 를 선 생성 (없으면 V5 가 fail-fast).
            pg.createConnection("").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE SCHEMA IF NOT EXISTS auth")
                    st.execute("""CREATE TABLE IF NOT EXISTS auth."user" (id TEXT PRIMARY KEY)""")
                }
            }

            val flyway =
                Flyway
                    .configure()
                    .dataSource(pg.jdbcUrl, pg.username, pg.password)
                    .locations("classpath:db/migration")
                    .load()

            val result = flyway.migrate()
            // V1부터 V42까지 전부 적용
            assertTrue(result.migrationsExecuted >= 42, "적용된 마이그레이션 수=${result.migrationsExecuted} (>=42 기대)")

            pg.createConnection("").use { conn ->
                val md = conn.metaData
                // V33: habit_trackers.version(@Version 낙관락) 컬럼 존재 실증 (감사 LOW#23)
                md.getColumns(null, null, "habit_trackers", "version").use { rs ->
                    assertTrue(rs.next(), "habit_trackers.version 부재 (V33 미적용)")
                }
                // V32: users.basic_coin / special_coin 컬럼 드롭 실증
                md.getColumns(null, null, "users", "basic_coin").use { rs ->
                    assertFalse(rs.next(), "users.basic_coin 이 아직 존재 (V32 드롭 실패)")
                }
                md.getColumns(null, null, "users", "special_coin").use { rs ->
                    assertFalse(rs.next(), "users.special_coin 이 아직 존재 (V32 드롭 실패)")
                }
                // V32: user_tokens 테이블 드롭 실증
                md.getTables(null, null, "user_tokens", arrayOf("TABLE")).use { rs ->
                    assertFalse(rs.next(), "user_tokens 테이블이 아직 존재 (V32 드롭 실패)")
                }
                // 신 substrate 테이블 존재 실증 (지갑 SoT)
                md.getTables(null, null, "user_currency_balances", arrayOf("TABLE")).use { rs ->
                    assertTrue(rs.next(), "user_currency_balances 테이블 부재 (신 지갑 substrate 미생성)")
                }
                // V36: tx_ref 처리 원장 (revoke 후 grant 재전송 멱등) 존재 실증
                md.getTables(null, null, "entitlement_tx_ledger", arrayOf("TABLE")).use { rs ->
                    assertTrue(rs.next(), "entitlement_tx_ledger 테이블 부재 (V36 미적용)")
                }
                // V42: 서버 nonce 수명주기 컬럼 존재 실증
                md.getColumns(null, null, "ad_reward_nonce_inbox", "status").use { rs ->
                    assertTrue(rs.next(), "ad_reward_nonce_inbox.status 부재 (V42 미적용)")
                }
                md.getColumns(null, null, "ad_reward_nonce_inbox", "verified_at").use { rs ->
                    assertTrue(rs.next(), "ad_reward_nonce_inbox.verified_at 부재 (V42 미적용)")
                }
                // V39 (아프젝 v2): 티어별 배치(active_tier/terrarium_items.tier) + 습관 사이클/페어 요청 + 키우기 사이클 컬럼 + items.purchasable
                md.getColumns(null, null, "terrariums", "active_tier").use { rs ->
                    assertTrue(rs.next(), "terrariums.active_tier 부재 (V39 미적용)")
                }
                md.getColumns(null, null, "terrarium_items", "tier").use { rs ->
                    assertTrue(rs.next(), "terrarium_items.tier 부재 (V39 미적용)")
                }
                md.getTables(null, null, "habit_cycles", arrayOf("TABLE")).use { rs ->
                    assertTrue(rs.next(), "habit_cycles 테이블 부재 (V39 미적용)")
                }
                md.getTables(null, null, "habit_pair_requests", arrayOf("TABLE")).use { rs ->
                    assertTrue(rs.next(), "habit_pair_requests 테이블 부재 (V39 미적용)")
                }
                md.getColumns(null, null, "growth_instances", "cycle_state").use { rs ->
                    assertTrue(rs.next(), "growth_instances.cycle_state 부재 (V39 미적용)")
                }
                md.getColumns(null, null, "items", "purchasable").use { rs ->
                    assertTrue(rs.next(), "items.purchasable 부재 (V39 미적용)")
                }
                // V40: 티어별 배경 테이블
                md.getTables(null, null, "terrarium_tier_backgrounds", arrayOf("TABLE")).use { rs ->
                    assertTrue(rs.next(), "terrarium_tier_backgrounds 테이블 부재 (V40 미적용)")
                }
                // V39 시드: 3레벨 루비 전용 티어 + 정령 아이템 + 배경 아이템
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT ruby_cost, slots, is_active FROM tier_configs WHERE tier = 'GRAND_TANK'").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getLong(1) == 50L && rs.getInt(2) == 40 && rs.getBoolean(3), "GRAND_TANK 재시드 불일치")
                    }
                    st.executeQuery("SELECT is_active FROM tier_configs WHERE tier = 'HOUSE_TANK'").use { rs ->
                        assertTrue(rs.next())
                        assertFalse(rs.getBoolean(1), "HOUSE_TANK 는 비활성이어야 함")
                    }
                    st.executeQuery("SELECT COUNT(*) FROM items WHERE slug IN ('cat-spirit','pigeon-spirit','fish-spirit') AND purchasable = FALSE").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getInt(1) == 3, "정령 아이템 3종 시드 부재")
                    }
                    st.executeQuery("SELECT COUNT(*) FROM items WHERE layout = 'BACKGROUND' AND purchasable = TRUE").use { rs ->
                        assertTrue(rs.next())
                        assertTrue(rs.getInt(1) >= 3, "배경 아이템 3종 이상 시드 부재")
                    }
                }
            }
        }
    }

    /**
     * V40 백필 의미 검증 — V39 까지 적용한 뒤 V38/V39 스타일 데이터(친구 습관 양측 트래커, solo 트래커, 테라리움 배경,
     * 진행 30 이상 키우기)를 넣고 V40 을 적용해 결과를 확인한다.
     */
    @Test
    fun `V40 백필 — 기존 친구 습관 페어 연결·사이클 행, 티어별 배경 이관, 키우기 완료(정령 지급)`() {
        assumeTrue(dockerAvailable(), "Docker 미가용 — 마이그레이션 스모크 skip (docker_unavailable)")

        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            pg.createConnection("").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("CREATE SCHEMA IF NOT EXISTS auth")
                    st.execute("""CREATE TABLE IF NOT EXISTS auth."user" (id TEXT PRIMARY KEY)""")
                }
            }

            fun flyway(target: String?) =
                Flyway
                    .configure()
                    .dataSource(pg.jdbcUrl, pg.username, pg.password)
                    .locations("classpath:db/migration")
                    .apply { if (target != null) target(target) }
                    .load()

            // 1) V39 까지
            flyway("39").migrate()

            // 2) V38/V39 스타일 픽스처
            pg.createConnection("").use { conn ->
                conn.createStatement().use { st ->
                    st.execute("""INSERT INTO auth."user"(id) VALUES ('u1'),('u2'),('u3')""")
                    st.execute("INSERT INTO users(id, nickname) VALUES ('u1','일'),('u2','이'),('u3','삼')")
                    st.execute("INSERT INTO terrarium_backgrounds(id, name, asset_url) VALUES (9001,'기본','bg.png'),(9002,'밤','night.png')")
                    // 해금 최고 LARGE_JAR, 표시 중 GLASS_JAR, 배경 9002
                    st.execute("INSERT INTO terrariums(id, user_id, background_id, tier, active_tier) VALUES (9001,'u1',9002,'LARGE_JAR','GLASS_JAR')")
                    // 친구 습관(link 77): u2 의 옛 BROKEN(9100) + u1/u2 ACTIVE(9101/9102)
                    st.execute(
                        """
                        INSERT INTO habit_trackers(id, user_id, title, start_date, current_streak_days, completed_cycles, status, friend_link_id) VALUES
                          (9100,'u2','옛것', CURRENT_DATE - 30, 0, 0, 'BROKEN', 77),
                          (9101,'u1','스트레칭', CURRENT_DATE - 9, 2, 1, 'ACTIVE', 77),
                          (9102,'u2','스트레칭', CURRENT_DATE - 3, 3, 0, 'ACTIVE', 77),
                          (9103,'u3','독서',     CURRENT_DATE - 1, 1, 0, 'ACTIVE', NULL),
                          (9104,'u3','완료됨',   CURRENT_DATE - 20, 0, 1, 'COMPLETED', 88),
                          (9105,'u1','한쪽만',   CURRENT_DATE - 2, 2, 0, 'ACTIVE', 99),
                          (9106,'u1','상대중단', CURRENT_DATE - 4, 4, 0, 'ACTIVE', 55),
                          (9107,'u3','상대중단', CURRENT_DATE - 4, 1, 0, 'BROKEN', 55)
                        """.trimIndent(),
                    )
                    // 키우기: u1 cat 35(완료 대상, 정령 지급) / u2 cat 29(유지) / u3 tomato-vine 30(완료, PLANT 라 지급 없음)
                    st.execute(
                        """
                        INSERT INTO growth_instances(user_id, species_code, natural_streak, sparkle_bought_count, last_progress_at) VALUES
                          ('u1','cat',25,10, NOW()), ('u2','cat',29,0, NOW()), ('u3','tomato-vine',30,0, NOW())
                        """.trimIndent(),
                    )
                }
            }

            // 3) V40 적용
            val result = flyway(null).migrate()
            assertTrue(result.migrationsExecuted >= 1, "V40 이 적용돼야 함")

            pg.createConnection("").use { conn ->
                conn.createStatement().use { st ->
                    fun one(sql: String): java.sql.ResultSet = st.executeQuery(sql).also { assertTrue(it.next(), "행 없음: $sql") }
                    // 티어별 배경: 표시 중 GLASS_JAR 행에 9002, 컬럼은 유지
                    one("SELECT background_id FROM terrarium_tier_backgrounds WHERE terrarium_id = 9001 AND tier = 'GLASS_JAR'").use { assertTrue(it.getLong(1) == 9002L) }
                    one("SELECT COUNT(*) FROM terrarium_tier_backgrounds WHERE terrarium_id = 9001").use { assertTrue(it.getInt(1) == 1) }
                    one("SELECT background_id FROM terrariums WHERE id = 9001").use { assertTrue(it.getLong(1) == 9002L) }

                    // 페어: 9101 <-> 9102 (ACTIVE 우선, 옛 BROKEN 9100 은 미연결), 9106 <-> 9107(BROKEN 상대), 9105 solo, 9104 COMPLETED 불변
                    one("SELECT partner_tracker_id FROM habit_trackers WHERE id = 9101").use { assertTrue(it.getLong(1) == 9102L) }
                    one("SELECT partner_tracker_id FROM habit_trackers WHERE id = 9102").use { assertTrue(it.getLong(1) == 9101L) }
                    one("SELECT partner_tracker_id IS NULL FROM habit_trackers WHERE id = 9100").use { assertTrue(it.getBoolean(1)) }
                    one("SELECT partner_tracker_id FROM habit_trackers WHERE id = 9106").use { assertTrue(it.getLong(1) == 9107L) }
                    one("SELECT partner_tracker_id FROM habit_trackers WHERE id = 9107").use { assertTrue(it.getLong(1) == 9106L) }
                    one("SELECT partner_tracker_id IS NULL AND current_cycle_id IS NULL FROM habit_trackers WHERE id = 9104").use { assertTrue(it.getBoolean(1)) }
                    one("SELECT partner_tracker_id IS NULL AND current_cycle_id IS NOT NULL FROM habit_trackers WHERE id = 9105").use { assertTrue(it.getBoolean(1)) }

                    // 사이클: ACTIVE 5건(9101,9102,9103,9105,9106)만, BROKEN/COMPLETED 없음
                    one("SELECT COUNT(*) FROM habit_cycles").use { assertTrue(it.getInt(1) == 5, "habit_cycles 수=${it.getInt(1)}") }
                    one("SELECT COUNT(*) FROM habit_trackers WHERE status = 'ACTIVE' AND current_cycle_id IS NULL").use { assertTrue(it.getInt(1) == 0) }
                    // 9101: cycle_no 2(completed 1), started_on = start+7, pair 200 / 9106: 상대 BROKEN → 100 / 9103 solo 100
                    one("SELECT c.cycle_no, c.started_on, c.reward_sparkle, t.start_date + 7 FROM habit_cycles c JOIN habit_trackers t ON t.id = c.tracker_id WHERE c.tracker_id = 9101").use {
                        assertTrue(it.getInt(1) == 2)
                        assertTrue(it.getDate(2) == it.getDate(4), "started_on=${it.getDate(2)} 기대=${it.getDate(4)}")
                        assertTrue(it.getLong(3) == 200L)
                    }
                    one("SELECT reward_sparkle FROM habit_cycles WHERE tracker_id = 9102").use { assertTrue(it.getLong(1) == 200L) }
                    one("SELECT reward_sparkle FROM habit_cycles WHERE tracker_id = 9106").use { assertTrue(it.getLong(1) == 100L) }
                    one("SELECT reward_sparkle, cycle_no FROM habit_cycles WHERE tracker_id = 9103").use { assertTrue(it.getLong(1) == 100L && it.getInt(2) == 1) }
                    one("SELECT COUNT(*) FROM habit_pair_requests").use { assertTrue(it.getInt(1) == 0) }

                    // 키우기: u1 cat COMPLETED + 내일 리셋 + cat-spirit 원장/소유, u2 ACTIVE, u3 PLANT COMPLETED 지급 없음
                    one("SELECT cycle_state, completed_kst_date, reset_due_kst_date FROM growth_instances WHERE user_id = 'u1'").use {
                        assertTrue(it.getString(1) == "COMPLETED")
                        assertTrue(it.getDate(3).toLocalDate() == it.getDate(2).toLocalDate().plusDays(1))
                    }
                    one("SELECT cycle_state FROM growth_instances WHERE user_id = 'u2'").use { assertTrue(it.getString(1) == "ACTIVE") }
                    one("SELECT cycle_state FROM growth_instances WHERE user_id = 'u3'").use { assertTrue(it.getString(1) == "COMPLETED") }
                    one("SELECT COUNT(*) FROM user_grants WHERE user_id = 'u1' AND grant_type = 'ITEM' AND grant_ref = 'cat-spirit' AND idempotency_key LIKE 'growth:%:spirit'").use { assertTrue(it.getInt(1) == 1) }
                    one("SELECT COUNT(*) FROM user_items ui JOIN items i ON i.id = ui.item_id WHERE ui.user_id = 'u1' AND i.slug = 'cat-spirit'").use { assertTrue(it.getInt(1) == 1) }
                    one("SELECT COUNT(*) FROM user_grants WHERE user_id IN ('u2','u3')").use { assertTrue(it.getInt(1) == 0) }
                }
            }
        }
    }

    private fun dockerAvailable(): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (e: Throwable) {
            false
        }
}
