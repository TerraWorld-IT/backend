package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/**
 * BE-01 (2026-07-15 성능 감사): 결제 webhook 의 Discord alert 를 요청/tx 스레드에서 분리.
 *
 * 기존: PlayBillingWebhookController 가 @Transactional 요청 스레드 안에서 동기 HTTP POST
 * (timeout 5s) — Discord 지연이 DB 커넥션 점유 시간을 그대로 늘리는 pool 고갈 벡터.
 *
 * 변경: `@Async("auditExecutor")` best-effort 발송. auditExecutor 재사용 이유 — alert 는
 * audit 와 동일한 저빈도 best-effort side-channel 계약(실패해도 WARN 만, CallerRunsPolicy
 * backpressure)이라 전용 pool 신설 없이 격리 요건을 충족한다 ([com.terraworld.common.audit.AuditAsyncConfig]).
 */
@Service
class BillingAlertService(
    private val objectMapper: ObjectMapper,
    @Value("\${terraworld.billing.alert-discord-webhook:}")
    private val alertDiscordWebhook: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    /** Discord webhook alert. 실패는 main flow 비차단 — log.warn 만 (best-effort). */
    @Async("auditExecutor")
    fun sendDiscordAlert(message: String) {
        if (alertDiscordWebhook.isBlank()) {
            log.info("billing.alert.discord-skip — webhook URL 미설정. message: {}", message)
            return
        }
        try {
            // Codex MED: 수동 escape 대신 Jackson 직렬화 — \r \t 등 제어문자/injection 안전.
            val body = objectMapper.writeValueAsString(mapOf("content" to message))
            val req =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(alertDiscordWebhook))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build()
            httpClient.send(req, HttpResponse.BodyHandlers.discarding())
        } catch (e: Exception) {
            log.warn("billing.alert.discord-failed msg={}", e.message)
        }
    }
}
