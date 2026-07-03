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
            // 최소 33개 마이그레이션(V1~V33) 적용
            assertTrue(result.migrationsExecuted >= 33, "적용된 마이그레이션 수=${result.migrationsExecuted} (>=33 기대)")

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
