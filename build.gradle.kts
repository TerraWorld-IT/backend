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

dependencies {
    // Spring Boot
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-data-redis")
    implementation("org.springframework.boot:spring-boot-starter-actuator")

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

    // Test
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
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
