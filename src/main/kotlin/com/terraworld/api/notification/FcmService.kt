package com.terraworld.api.notification

import com.google.auth.oauth2.GoogleCredentials
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingException
import com.google.firebase.messaging.Message
import com.google.firebase.messaging.Notification
import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Service
import java.io.ByteArrayInputStream

/**
 * UltraPlan M1 — FCM (Firebase Cloud Messaging) push 발송.
 *
 * 초기화 정책:
 *   1. `FCM_SERVICE_ACCOUNT_JSON` env 변수 (raw JSON 문자열) 가 있으면 그것으로 init
 *   2. 부재 시 `GOOGLE_APPLICATION_CREDENTIALS` (path) 의 firebase-admin 자동 검출에 위임
 *   3. 둘 다 부재 시 **noop mode** — 모든 send 호출이 log + skip + counter 증가. 테스트/dev 환경 무 secret 가정.
 *
 * 보안:
 *   - service account JSON 은 절대 log 에 노출 금지 (Spring property 직접 binding 안 함)
 *   - token 자체도 log 비노출 — UserDevice.tokenFingerprint() 사용
 *
 * 운영 (SRE-001):
 *   - Micrometer counter 4종 (`fcm.send.success` / `fcm.send.failure` / `fcm.send.noop` / `fcm.init.failure`)
 *     으로 noop fallback / send fail 가시화. /actuator/prometheus scrape → Grafana / Sentry alert 트리거.
 *
 * 본 service 는 stub 단계. domain event listener (RecordCreated, AttendanceRewardClaimed 등) 와의
 * wiring 은 후속 PR — UltraPlan M1 의 implementation 단계.
 */
@Service
class FcmService(
    private val messagingOrNoop: FirebaseMessaging?,
    private val meterRegistry: MeterRegistry,
) {
    private val log = LoggerFactory.getLogger(FcmService::class.java)

    companion object {
        // UX-004: FCM push title 길이 제한 (iOS Notification Service Extension 권장 30자
        // / Android 38-39자). 양쪽 호환 위해 30자 truncate.
        const val MAX_TITLE_LENGTH = 30

        // body 도 alert/lock-screen 표시 길이 제한 (iOS ~120, Android ~240). 보수적 90자.
        const val MAX_BODY_LENGTH = 90

        internal fun truncatePushText(
            text: String,
            maxLength: Int,
        ): String {
            if (text.length <= maxLength) return text
            // Codex final audit MEDIUM 1 — surrogate pair guard. 보조 평면 이모지 (U+1F300+)
            // 는 UTF-16 high+low surrogate pair (2 code unit). substring(0, maxLength-1) 가
            // high surrogate 직후를 자르면 malformed string. cut 위치 직전이 high surrogate
            // 면 cut 위치를 1 더 앞당겨 surrogate pair 보존.
            var cutAt = maxLength - 1
            if (cutAt > 0 && Character.isHighSurrogate(text[cutAt - 1])) {
                cutAt -= 1
            }
            return text.substring(0, cutAt) + "…"
        }
    }

    // SRE-001 — push 발송 결과 가시화 counter. tag 로 channel (token vs topic) + outcome 구분.
    private val sendSuccess: Counter =
        Counter
            .builder("fcm.send")
            .description("FCM push send results")
            .tag("outcome", "success")
            .register(meterRegistry)
    private val sendFailure: Counter =
        Counter
            .builder("fcm.send")
            .description("FCM push send results")
            .tag("outcome", "failure")
            .register(meterRegistry)
    private val sendNoop: Counter =
        Counter
            .builder("fcm.send")
            .description("FCM push send results")
            .tag("outcome", "noop")
            .register(meterRegistry)

    /**
     * 단일 디바이스 token 에 push 전송.
     * @return true = 발송 성공 또는 noop 모드 / false = Firebase 가 invalid token 보고
     */
    fun sendToToken(
        token: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ): Boolean {
        if (messagingOrNoop == null) {
            // SEC-402 (code-review): noop log 를 debug 강등 — title/body.length 가 PII 노출 가능성.
            // production 진입 시 noop mode 자체가 alert 대상 (fcm.init.failure counter).
            log.debug("FCM noop mode — title chars={} body chars={} dataKeys={}", title.length, body.length, data.keys)
            sendNoop.increment()
            return true
        }
        val message =
            Message
                .builder()
                .setToken(token)
                .setNotification(
                    Notification
                        .builder()
                        .setTitle(truncatePushText(title, MAX_TITLE_LENGTH))
                        .setBody(truncatePushText(body, MAX_BODY_LENGTH))
                        .build(),
                ).putAllData(data)
                .build()
        return try {
            val response = messagingOrNoop.send(message)
            log.info("FCM send ok: messageId={}", response)
            sendSuccess.increment()
            true
        } catch (e: FirebaseMessagingException) {
            // 무효 토큰 (registration-token-not-registered, invalid-argument) 는
            // domain layer 에서 UserDevice.isActive=false 로 invalidate 권장.
            log.warn("FCM send failed: errorCode={} message={}", e.messagingErrorCode, e.message)
            sendFailure.increment()
            false
        }
    }

    /**
     * Topic broadcast (e.g. "weekly-events").
     */
    fun sendToTopic(
        topic: String,
        title: String,
        body: String,
        data: Map<String, String> = emptyMap(),
    ): Boolean {
        if (messagingOrNoop == null) {
            // SEC-402 (code-review): noop log 강등. topic 명은 enumeration risk 적으나 일관성.
            log.debug("FCM noop topic mode — topic={} title chars={}", topic, title.length)
            sendNoop.increment()
            return true
        }
        val message =
            Message
                .builder()
                .setTopic(topic)
                .setNotification(
                    Notification
                        .builder()
                        .setTitle(truncatePushText(title, MAX_TITLE_LENGTH))
                        .setBody(truncatePushText(body, MAX_BODY_LENGTH))
                        .build(),
                ).putAllData(data)
                .build()
        return try {
            val response = messagingOrNoop.send(message)
            log.info("FCM topic send ok: topic={} messageId={}", topic, response)
            sendSuccess.increment()
            true
        } catch (e: FirebaseMessagingException) {
            log.warn("FCM topic send failed: topic={} errorCode={}", topic, e.messagingErrorCode)
            sendFailure.increment()
            false
        }
    }
}

/**
 * Firebase Admin SDK 초기화. service account JSON 부재 시 null bean — FcmService 가 noop 모드 진입.
 * SRE-001: init failure 도 counter 로 가시화 (운영자가 service account rotation 누락 검출 가능).
 */
@Configuration
class FcmConfiguration {
    private val log = LoggerFactory.getLogger(FcmConfiguration::class.java)

    @Bean
    fun firebaseMessaging(
        @Value("\${FCM_SERVICE_ACCOUNT_JSON:}") serviceAccountJson: String,
        @Value("\${GOOGLE_APPLICATION_CREDENTIALS:}") googleAppCredsPath: String,
        meterRegistry: MeterRegistry,
    ): FirebaseMessaging? {
        val initFailureCounter: Counter =
            Counter
                .builder("fcm.init.failure")
                .description("FCM init failures — noop fallback 진입 횟수. 운영자가 service account rotation 누락 / 잘못된 JSON / 통신 fail 검출")
                .register(meterRegistry)
        val app: FirebaseApp? =
            try {
                when {
                    serviceAccountJson.isNotBlank() -> {
                        // SEC-203 fix: credential length 로그 제거. parse 실패 시 catch 의 stacktrace
                        // 만으로 진단 충분. length 노출은 internal-only 환경에서도 외부 log
                        // aggregator 도입 시 metadata leak 위험.
                        log.info("FCM init via FCM_SERVICE_ACCOUNT_JSON env")
                        val creds = GoogleCredentials.fromStream(ByteArrayInputStream(serviceAccountJson.toByteArray(Charsets.UTF_8)))
                        val options = FirebaseOptions.builder().setCredentials(creds).build()
                        if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options) else FirebaseApp.getInstance()
                    }
                    googleAppCredsPath.isNotBlank() -> {
                        log.info("FCM init via GOOGLE_APPLICATION_CREDENTIALS={}", googleAppCredsPath)
                        val options = FirebaseOptions.builder().setCredentials(GoogleCredentials.getApplicationDefault()).build()
                        if (FirebaseApp.getApps().isEmpty()) FirebaseApp.initializeApp(options) else FirebaseApp.getInstance()
                    }
                    else -> {
                        log.warn("FCM service account 부재 — noop mode 진입. push 발송이 silently skip 됨.")
                        initFailureCounter.increment()
                        null
                    }
                }
            } catch (e: Exception) {
                // SEC-204 + SRE-001: stacktrace 보존 + counter 증가
                log.error("FCM init failed — noop mode fallback", e)
                initFailureCounter.increment()
                null
            }
        return app?.let { FirebaseMessaging.getInstance(it) }
    }
}
