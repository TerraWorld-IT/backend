plugins {
    id("org.springframework.boot") version "3.4.4"
    id("io.spring.dependency-management") version "1.1.7"
    kotlin("jvm") version "2.1.10"
    kotlin("plugin.spring") version "2.1.10"
    kotlin("plugin.jpa") version "2.1.10"
    id("org.jlleitschuh.gradle.ktlint") version "12.1.1"
    jacoco
}

group = "com.terraworld"
version = "0.0.1-SNAPSHOT"

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

// LOCK-1 (Gradle 의존성 잠금) 은 검토 후 **도입하지 않기로** 했다.
// lockAllConfigurations() 자체는 동작했으나, dependabot 의 gradle ecosystem 과 상호
// 배타적이다: dependabot 은 build.gradle.kts 의 버전만 바꾸고 lockfile 을 재생성하지
// 못하므로 모든 bump PR 이 `Did not resolve ...` 로 빨갛게 도착하고, 매번 사람이
// `./gradlew dependencies --write-locks` 를 돌려 커밋해야 한다 — 자동화를 도입한
// 목적 자체를 상쇄하는 상시 수작업이다.
// transitive 버전의 대부분은 이미 Spring Boot BOM 이 고정하고 있어 실익도 제한적이다.
// 재검토 조건: dependabot gradle 블록을 제거하거나, lockfile 자동 재생성 워크플로를
// 갖춘 뒤에 다시 검토한다.

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // BE-08 (2026-07-15 성능 감사) — 정적 config(화폐/티어/육성/카탈로그/카테고리) 로컬 캐시.
    // CacheConfig 가 CaffeineCacheManager 를 명시 @Bean 으로 선언 — redis starter 가 classpath 에
    // 있어도 auto-config 가 Redis cache 로 잡지 않는다 (로컬 인메모리 의도).
    implementation("org.springframework.boot:spring-boot-starter-cache")
    implementation("com.github.ben-manes.caffeine:caffeine")

    // Kotlin
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Database
    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // JWT
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")

    // OpenAPI
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.4")

    // Spec-first API stubs (generated from TerraWorld-IT/openapi via composite build).
    // Controllers implement interfaces in io.terraworld.api.api.* — compile fails
    // if the spec drifts from the controller signature.
    implementation("io.terraworld:openapi-backend")

    // UltraPlan M1 — FCM (Firebase Cloud Messaging).
    // Service account JSON 은 환경변수 FCM_SERVICE_ACCOUNT_JSON 또는 GOOGLE_APPLICATION_CREDENTIALS
    // path 로 주입. 부재 시 FcmService bean 가 noop mode 로 init (테스트/dev 환경).
    implementation("com.google.firebase:firebase-admin:9.4.3")

    // WP-4 (2026-06-04) — Cloudflare R2(S3 호환) 사진 업로드. R2_* 미설정 시 base64 PoC 유지.
    implementation(platform("software.amazon.awssdk:bom:2.28.16"))
    implementation("software.amazon.awssdk:s3")

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    // 파괴적 마이그레이션(V32) 런타임 검증: 실 Postgres 에 V1→Vn Flyway 전수 적용 스모크
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// JaCoCo — 도입 1단계: 리포트만 생성 (XML/HTML), threshold 미강제.
// 실제 baseline 측정 후 다음 PR 에서 jacocoTestCoverageVerification 추가 예정.
// generated openapi-backend stub (io.terraworld.*) 와 Spring Boot 진입점은 제외.
jacoco {
    toolVersion = "0.8.12"
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)
    reports {
        xml.required.set(true)
        html.required.set(true)
        csv.required.set(false)
    }
    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude(
                        "**/io/terraworld/**",
                        "**/com/terraworld/TerraworldApplication*",
                    )
                }
            },
        ),
    )
}

tasks.test {
    finalizedBy(tasks.jacocoTestReport)
}

// Spring Boot Gradle 플러그인은 기본적으로 bootJar(실행용 fat jar) 와 함께
// jar(의존성 없는 plain jar, *-plain.jar) 도 생성한다.
// Dockerfile 의 `COPY build/libs/*.jar app.jar` 는 목적지가 단일 파일이라
// build/libs 에 jar 가 둘 이상 있으면 어느 것이 복사될지 알파벳 정렬 순서에
// 우연히 의존하게 된다 — plain jar 생성 자체를 꺼서 build/libs 에 fat jar
// 하나만 남도록 한다.
tasks.named<Jar>("jar") {
    enabled = false
}

// ktlint Kotlin 린트. openapi-backend submodule(자동 생성 stub)은 검사 제외.
// 첫 도입 시 위반이 있으면 ./gradlew ktlintFormat 으로 자동 수정 또는
// ./gradlew ktlintCheck --baseline 으로 baseline 생성 가능.
ktlint {
    // ktlint 본 라이브러리 버전 명시 — Kotlin 2.1.x 호환을 위해 1.5.0 사용
    // (plugin 12.1.1 의 default 가 Kotlin 버전 호환성 issue 가 있어 명시).
    version.set("1.5.0")
    verbose.set(true)
    android.set(false)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    enableExperimentalRules.set(false)
    filter {
        exclude { it.file.path.contains("openapi-backend") }
        exclude { it.file.path.contains("/build/") }
        exclude { it.file.path.contains("/generated/") }
    }
    reporters {
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.CHECKSTYLE)
    }
}
