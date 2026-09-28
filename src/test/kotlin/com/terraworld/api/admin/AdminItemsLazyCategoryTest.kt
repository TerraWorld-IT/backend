package com.terraworld.api.admin

import com.terraworld.common.audit.AuditService
import jakarta.persistence.EntityManagerFactory
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.data.jpa.repository.config.EnableJpaRepositories
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.orm.jpa.JpaTransactionManager
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer
import java.util.function.Supplier
import javax.sql.DataSource
import kotlin.test.assertEquals

/**
 * 결함 재현: `AdminController.listAllItems` 가 트랜잭션 밖에서 `ItemMapper.toApi` 로
 * lazy `Item.category` 를 읽으면 실 세션 없이 접근해 LazyInitializationException(500) 이 난다.
 * 수정 전 코드에서는 본 테스트가 그 예외로 실패한다.
 */
class AdminItemsLazyCategoryTest {
    @Test
    fun `관리자 아이템 목록은 lazy category 를 트랜잭션 안에서 매핑해 이름을 포함한 응답을 반환한다`() {
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
            // V2__seed_data.sql 이 심는 시스템 카테고리 1번(산책)에 연결된 아이템 하나를 준비한다.
            jdbc.update(
                "INSERT INTO items (name, category_id, price_type, price_amount, asset_url) VALUES (?, 1, 'BASIC', 100, 'lazy-category-fixture')",
                "lazy-category-fixture-item",
            )

            AnnotationConfigApplicationContext().use { context ->
                context.registerBean(DataSource::class.java, Supplier { dataSource })
                context.registerBean(AuditService::class.java, Supplier { mock<AuditService>() })
                context.register(JpaConfig::class.java)
                context.refresh()

                val controller = context.getBean(AdminController::class.java)
                val response = controller.listAllItems()

                assertEquals(HttpStatus.OK, response.statusCode)
                val item = response.body!!.items.single { it.name == "lazy-category-fixture-item" }
                assertEquals("산책", item.categoryName)
                println("ADMIN_ITEMS_LAZY_CATEGORY_EXECUTED: PostgreSQL 16; category_id=1 name matched; skipped=0")
            }
        }
    }

    private fun dockerAvailable(): Boolean =
        try {
            DockerClientFactory.instance().isDockerAvailable
        } catch (ex: Throwable) {
            false
        }

    @Configuration
    @EnableTransactionManagement(proxyTargetClass = true)
    @EnableJpaRepositories("com.terraworld.domain")
    @Import(AdminService::class, AdminController::class)
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
