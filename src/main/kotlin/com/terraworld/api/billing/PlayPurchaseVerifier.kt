package com.terraworld.api.billing

import com.fasterxml.jackson.databind.ObjectMapper
import com.google.auth.oauth2.GoogleCredentials
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.io.FileInputStream
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration

/**
 * Google Play Developer API 로 purchaseToken 의 진위 + user 매핑을 검증한다.
 * (fullstack-ultraplan WP-1, 2026-06-04 실 API 구현)
 *
 * 구현 메모: 무거운 `google-api-services-androidpublisher` SDK 대신, 이미 firebase-admin 이
 * 제공하는 `com.google.auth:google-auth-library-oauth2-http` + JDK `java.net.http` 로 REST 호출
 * → 신규 의존성 0.
 *   GET https://androidpublisher.googleapis.com/androidpublisher/v3/applications/{pkg}/purchases/products/{productId}/tokens/{token}
 *   scope = https://www.googleapis.com/auth/androidpublisher
 *
 * 응답 `purchaseState`: 0=PURCHASED · 1=CANCELED · 2=PENDING.
 * 응답 `obfuscatedExternalAccountId`: client 가 구매 시 setObfuscatedAccountId(userId) 로 심은 값.
 *   존재 시 expectedUserId 와 cross-check(다른 user 의 token replay 차단). 부재 시 skip(권고: client 가 설정).
 *
 * gating: `terraworld.billing.play.service-account-json-path` 가 비면 [VerificationResult.disabled]
 *   → caller(IapVerifyController/Webhook)가 test-mode/개발 경로로 처리. 운영(prod)은 주입 필수.
 *
 * ⚠️ 실 purchaseToken 트래픽 미검증(.env-ready) — Play Console service account 주입 + 내부테스트
 *   트랙 실구매 후 1회 검증 필요.
 */
@Component
class PlayPurchaseVerifier(
    @Value("\${terraworld.billing.play.package-name:app.terraworld.mobile}")
    private val packageName: String,
    @Value("\${terraworld.billing.play.service-account-json-path:}")
    private val serviceAccountJsonPath: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    /**
     * BE-13 (2026-07-15 성능 감사): GoogleCredentials 를 호출마다 fromStream 재생성하면
     * 내부 OAuth access-token 캐시가 매번 버려져 검증마다 파일 IO + 토큰 발급 round-trip 발생 →
     * lazy 필드로 1회 생성 승격. kotlin lazy 기본 mode = SYNCHRONIZED (thread-safe 1회 초기화,
     * 초기화 실패 시 미초기화 상태 유지 — 다음 호출이 재시도). refreshIfExpired() 는 호출마다 유지
     * (GoogleCredentials 내부 동기화로 thread-safe, 만료 시에만 실제 갱신).
     * serviceAccountJsonPath 가 blank 면 caller(verifyToken/resolveObfuscatedAccountId)가
     * 선분기하므로 본 필드는 절대 초기화되지 않는다.
     */
    private val credentials: GoogleCredentials by lazy {
        // Codex 보안 LOW: FileInputStream use{} 로 FD 누수 방지.
        FileInputStream(serviceAccountJsonPath).use { stream ->
            GoogleCredentials
                .fromStream(stream)
                .createScoped(listOf("https://www.googleapis.com/auth/androidpublisher"))
        }
    }

    /**
     * service-account 주입 여부 = 실 Play API 검증 가능 상태.
     * caller(RTDN/IAP)가 "검증 비활성(미설정)" 과 "검증 결과 not-found" 를 구분하게 한다
     * (Codex SEC-002: prod 미설정 시 silent-drop 대신 fail-loud 분기).
     */
    fun isEnabled(): Boolean = serviceAccountJsonPath.isNotBlank()

    fun verifyToken(
        productId: String,
        purchaseToken: String,
        expectedUserId: String,
    ): VerificationResult {
        if (serviceAccountJsonPath.isBlank()) {
            log.info("billing.verify.disabled — service-account-json-path 없음(개발/test-mode 경로)")
            return VerificationResult.disabled()
        }
        return try {
            val accessToken = fetchAccessToken()
            val url =
                "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
                    "${enc(packageName)}/purchases/products/${enc(productId)}/tokens/${enc(purchaseToken)}"
            val req =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $accessToken")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            when (res.statusCode()) {
                200 -> parsePurchase(res.body(), expectedUserId)
                404, 410 -> VerificationResult.invalidToken("not_found_or_consumed")
                else -> {
                    log.warn("billing.verify.api-status status={} product={}", res.statusCode(), productId)
                    VerificationResult.invalidToken("api_status_${res.statusCode()}")
                }
            }
        } catch (e: Exception) {
            log.error("billing.verify.error product={} msg={}", productId, e.message)
            // 검증 불능 = 안전하게 invalidToken(grant 금지). caller 가 4xx.
            VerificationResult.invalidToken("verify_exception")
        }
    }

    /**
     * resolveObfuscatedAccountId 결과 — "확정 부재"와 "조회 실패"를 분리한다.
     *
     * 구 시그니처는 둘 다 null 로 뭉갰고, RTDN 웹훅이 null 을 genuine not-found 로 간주해
     * 200 ACK → Pub/Sub 재전송 차단 → 일시 장애(네트워크/5xx/자격증명) 중 도착한 구매/취소
     * 알림이 영구 유실됐다 (2026-07-20 audit B1-4, lossy failure sentinel).
     */
    sealed interface AccountResolution {
        /** 조회 성공 + 계정 매핑 존재. */
        data class Resolved(
            val userId: String,
        ) : AccountResolution

        /** 확정 부재(404/410 토큰 미존재, 200 인데 obfuscated id 없음 등) — ACK 하고 버려도 안전. */
        data object Absent : AccountResolution

        /** 일시 실패(네트워크/타임아웃/5xx/401·403/미설정) — ACK 금지, 재전송을 유도해야 함. */
        data object Unavailable : AccountResolution
    }

    /**
     * RTDN(Pub/Sub) 처리용 — purchaseToken 의 obfuscatedExternalAccountId(=userId) 조회.
     * (fullstack-ultraplan WP-1-F)
     */
    fun resolveObfuscatedAccountId(
        productId: String,
        purchaseToken: String,
    ): AccountResolution {
        if (serviceAccountJsonPath.isBlank()) return AccountResolution.Unavailable
        return try {
            val accessToken = fetchAccessToken()
            val url =
                "https://androidpublisher.googleapis.com/androidpublisher/v3/applications/" +
                    "${enc(packageName)}/purchases/products/${enc(productId)}/tokens/${enc(purchaseToken)}"
            val req =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer $accessToken")
                    .timeout(Duration.ofSeconds(8))
                    .GET()
                    .build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            when {
                res.statusCode() == 200 -> {
                    val id =
                        objectMapper
                            .readTree(res.body())
                            .get("obfuscatedExternalAccountId")
                            ?.asText()
                            ?.takeIf { it.isNotBlank() }
                    if (id != null) AccountResolution.Resolved(id) else AccountResolution.Absent
                }
                // 404/410 = 토큰 자체가 없거나 소비됨 — 재시도해도 결과가 바뀌지 않는 확정 부재.
                res.statusCode() == 404 || res.statusCode() == 410 -> AccountResolution.Absent
                else -> {
                    // 401/403(자격증명 회전)·429·5xx — 재시도로 회복 가능. ACK 금지.
                    log.warn("billing.resolve-account.api-status status={} product={}", res.statusCode(), productId)
                    AccountResolution.Unavailable
                }
            }
        } catch (e: Exception) {
            log.warn("billing.resolve-account.error product={} msg={}", productId, e.message)
            AccountResolution.Unavailable
        }
    }

    private fun fetchAccessToken(): String {
        // BE-13: credentials 는 lazy 1회 생성 — 토큰은 만료 시에만 갱신 (재사용).
        credentials.refreshIfExpired()
        return credentials.accessToken.tokenValue
    }

    internal fun parsePurchase(
        body: String,
        expectedUserId: String,
    ): VerificationResult {
        val node = objectMapper.readTree(body)
        val purchaseState = node.get("purchaseState")?.asInt() ?: -1
        if (purchaseState != 0) {
            return VerificationResult.invalidToken("purchase_state_$purchaseState")
        }
        // Codex 보안 HIGH: obfuscatedExternalAccountId 부재 시 user-binding 불가 → 다른 user 가
        // 동일 purchaseToken 을 제출해 grant 받는 replay 위험. client(usePayment)가 구매 시
        // obfuscatedAccountId=userId 를 심어야 하며, 부재는 fail-closed 로 거부.
        val obfuscated = node.get("obfuscatedExternalAccountId")?.asText()
        if (obfuscated.isNullOrBlank()) {
            return VerificationResult.invalidToken("missing_obfuscated_account_id")
        }
        if (obfuscated != expectedUserId) {
            return VerificationResult.userMismatch(expectedUserId, obfuscated)
        }
        return VerificationResult.ok()
    }

    private fun enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)
}

/**
 * Verification 결과 — caller 가 grant/revoke 진입 여부 결정.
 *  - [Ok]: Google API 확인 + user 매핑 일치 → grant 진행
 *  - [Disabled]: service-account 미주입(개발/test-mode) — caller 가 별도 처리
 *  - [UserMismatch]: user 매핑 불일치 → 401 + audit
 *  - [InvalidToken]: purchaseToken 무효/만료/검증 불능 → 400 + audit
 */
sealed class VerificationResult {
    object Ok : VerificationResult()

    object Disabled : VerificationResult()

    data class UserMismatch(
        val expected: String,
        val actual: String,
    ) : VerificationResult()

    data class InvalidToken(
        val reason: String,
    ) : VerificationResult()

    companion object {
        fun ok(): VerificationResult = Ok

        fun disabled(): VerificationResult = Disabled

        fun userMismatch(
            expected: String,
            actual: String,
        ): VerificationResult = UserMismatch(expected, actual)

        fun invalidToken(reason: String): VerificationResult = InvalidToken(reason)
    }
}
