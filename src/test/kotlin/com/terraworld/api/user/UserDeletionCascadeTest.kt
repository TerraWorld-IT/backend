package com.terraworld.api.user

import com.terraworld.api.upload.R2PhotoStorage
import com.terraworld.api.userdevice.UserDeviceService
import com.terraworld.common.audit.AuditService
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.jdbc.datasource.init.ScriptUtils
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.function.Supplier
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 실제 Flyway 스키마와 JPA 서비스 트랜잭션으로 삭제·보존·롤백을 검증한다. */
class UserDeletionCascadeTest {
    @Test
    fun `실제 PostgreSQL에서 처분표 cascade 보존과 커밋 이후 사진 삭제를 검증한다`() {
        assumeTrue(dockerAvailable(), "미검증, 사유: docker_unavailable")
        PostgreSQLContainer("postgres:16-alpine").use { pg ->
            pg.start()
            val dataSource = DriverManagerDataSource(pg.jdbcUrl, pg.username, pg.password)
            val jdbc = JdbcTemplate(dataSource)
            jdbc.execute("CREATE SCHEMA auth")
            jdbc.execute("""CREATE TABLE auth."user" (id TEXT PRIMARY KEY)""")
            Flyway
                .configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            dataSource.connection.use { ScriptUtils.executeSqlScript(it, ClassPathResource("user-deletion-fixture.sql")) }

            val photoStorage: R2PhotoStorage = mock()
            whenever(photoStorage.ownsPublicUrl(any())).thenAnswer {
                (it.arguments[0] as String).startsWith("https://photos.example/photos/")
            }
            val photoDeletes = mutableListOf<String>()
            doAnswer {
                // 별도 JDBC 연결에서도 삭제 결과가 보여야 실제 커밋 이후다.
                assertEquals(0, count(jdbc, "users", "id = 'deleted-user'"))
                photoDeletes.add(it.arguments[0] as String)
                throw IllegalStateException("R2 실패 주입")
            }.whenever(photoStorage).delete(any())

            AnnotationConfigApplicationContext().use { context ->
                context.registerBean(DataSource::class.java, Supplier { dataSource })
                context.registerBean(R2PhotoStorage::class.java, Supplier { photoStorage })
                context.registerBean(AuditService::class.java, Supplier { mock<AuditService>() })
                context.register(JpaConfig::class.java)
                context.refresh()
                val service = context.getBean(UserDeletionService::class.java)
                val devices = context.getBean(UserDeviceService::class.java)
                devices.deactivateAll("deleted-user")
                devices.deactivateAll("deleted-user")
                assertEquals(0, count(jdbc, "user_devices", "user_id = 'deleted-user' AND is_active = TRUE"))
                assertEquals(1, count(jdbc, "user_devices", "user_id = 'keep-user' AND is_active = TRUE"))
                val transactions = TransactionTemplate(context.getBean(PlatformTransactionManager::class.java))
                val predicates = deletionPredicates()
                predicates.forEach { (table, predicate) ->
                    assertTrue(count(jdbc, table, predicate) > 0, "$table 삭제 대상 fixture가 있어야 한다")
                }
                val keepCounts = retainedUserCounts(jdbc)
                val ledgerBefore = jdbc.queryForList("SELECT * FROM entitlement_tx_ledger ORDER BY id")
                val auditBefore = jdbc.queryForList("SELECT * FROM audit_logs ORDER BY id")

                transactions.executeWithoutResult { status ->
                    service.deleteUser("deleted-user")
                    status.setRollbackOnly()
                }
                verify(photoStorage, never()).delete(any())
                predicates.forEach { (table, predicate) ->
                    assertTrue(count(jdbc, table, predicate) > 0, "$table 롤백 후 보존")
                }

                service.deleteUser("deleted-user")
                service.deleteUser("deleted-user")
                service.deleteUser("never-existed")
                predicates.forEach { (table, predicate) ->
                    assertEquals(0, count(jdbc, table, predicate), "$table 삭제 결과")
                }
                assertEquals(keepCounts, retainedUserCounts(jdbc), "상대방 소유 데이터 보존")
                assertEquals(ledgerBefore, jdbc.queryForList("SELECT * FROM entitlement_tx_ledger ORDER BY id"))
                assertEquals(auditBefore, jdbc.queryForList("SELECT * FROM audit_logs ORDER BY id"))
                assertEquals(0, count(jdbc, "activity_records", "partner_user_id = 'deleted-user'"))
                assertEquals(0, count(jdbc, "invites", "invitee_user_id = 'deleted-user'"))
                assertEquals(1, count(jdbc, "habit_trackers", "user_id = 'keep-user' AND status = 'ACTIVE'"))
                assertEquals(1, count(jdbc, "habit_cycles", "user_id = 'keep-user' AND cycle_no = 1"))
                assertEquals(2, count(jdbc, "auth.\"user\"", "TRUE"), "인증 계정 삭제는 인증 서버 책임")
                assertEquals(2, photoDeletes.size, "soft-delete 사진 포함, 중복 제거, 실패 후 다음 사진도 시도")
                assertTrue(photoDeletes.contains("https://photos.example/photos/00000000-0000-0000-0000-000000000002.png"))
                println("USER_DELETION_CASCADE_EXECUTED: PostgreSQL 16; ${predicates.size} deletion predicates zero; retained ledger/audit unchanged; peer data preserved; rollback and afterCommit verified; skipped=0")
            }
        }
    }

    private fun deletionPredicates(): Map<String, String> =
        buildMap {
            put("users", "id = 'deleted-user'")
            listOf(
                "activity_records",
                "wallet_transactions",
                "user_items",
                "terrariums",
                "day_notes",
                "attendance_logs",
                "user_entitlement",
                "entitlement_event",
                "wilt_notification_marker",
                "user_currency_balances",
                "user_grants",
                "user_characters",
                "growth_instances",
                "user_notifications",
                "todo_routines",
                "ad_watch_logs",
                "terrarium_placement_history",
                "user_devices",
                "habit_trackers",
                "habit_cycles",
                "ad_reward_nonce_inbox",
                "exchange_daily_usage",
            ).forEach { put(it, "user_id = 'deleted-user'") }
            put("categories", "owner_user_id = 'deleted-user'")
            put("terrarium_like", "liker_user_id = 'deleted-user' OR target_user_id = 'deleted-user'")
            put("habit_cheers", "from_user_id = 'deleted-user' OR to_user_id = 'deleted-user'")
            put("habit_pair_requests", "requester_user_id = 'deleted-user' OR partner_user_id = 'deleted-user'")
            put("invites", "inviter_user_id = 'deleted-user'")
            put("terrarium_items", "terrarium_id = 9001")
            put("terrarium_tier_backgrounds", "terrarium_id = 9001")
        }

    private fun retainedUserCounts(jdbc: JdbcTemplate): Map<String, Int> =
        buildMap {
            deletionPredicates().filterValues { it == "user_id = 'deleted-user'" }.keys.forEach {
                put(it, count(jdbc, it, "user_id = 'keep-user'"))
            }
            put("users", count(jdbc, "users", "id = 'keep-user'"))
            put("categories", count(jdbc, "categories", "owner_user_id = 'keep-user'"))
            put("terrarium_items", count(jdbc, "terrarium_items", "terrarium_id = 9002"))
            put("terrarium_tier_backgrounds", count(jdbc, "terrarium_tier_backgrounds", "terrarium_id = 9002"))
        }

    private fun count(
        jdbc: JdbcTemplate,
        table: String,
        predicate: String,
    ): Int = jdbc.queryForObject("SELECT COUNT(*) FROM $table WHERE $predicate", Int::class.java)!!

    private fun dockerAvailable(): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (ex: Throwable) {
            false
        }

    @Configuration
    @EnableTransactionManagement
    @EnableJpaRepositories("com.terraworld.domain")
    @Import(UserDeletionService::class, UserDeviceService::class)
    class JpaConfig {
        @Bean
        fun entityManagerFactory(dataSource: DataSource): LocalContainerEntityManagerFactoryBean =
            LocalContainerEntityManagerFactoryBean().apply {
                setDataSource(dataSource)
                setPackagesToScan("com.terraworld.domain")
                jpaVendorAdapter = HibernateJpaVendorAdapter()
                setJpaPropertyMap(mapOf("hibernate.hbm2ddl.auto" to "none"))
            }

        @Bean
        fun transactionManager(entityManagerFactory: EntityManagerFactory): PlatformTransactionManager = JpaTransactionManager(entityManagerFactory)
    }
}
