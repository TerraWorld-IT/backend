package com.terraworld.api.health

import io.terraworld.api.api.HealthApi
import io.terraworld.api.model.HealthStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 공개 상태 확인 API.
 *
 * 실행 JAR 의 구현 버전을 우선 사용하고, 로컬 클래스 실행처럼 manifest 정보가 없을 때는
 * Gradle 프로젝트 버전으로 응답한다.
 */
@RestController
@RequestMapping("/api/v1")
class HealthController : HealthApi {
    private val version = HealthController::class.java.`package`.implementationVersion ?: "0.0.1-SNAPSHOT"

    override fun getHealth(): ResponseEntity<HealthStatus> =
        ResponseEntity.ok(
            HealthStatus(
                status = HealthStatus.Status.UP,
                version = version,
                timestamp = OffsetDateTime.now(ZoneOffset.UTC),
            ),
        )
}
