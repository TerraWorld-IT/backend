package com.terraworld.api.reward

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.time.Instant
import java.util.Base64

/**
 * AdMob Rewarded Server-Side Verification (SSV) 서명 검증기.
 *
 * 공식 알고리즘 (developers.google.com/admob/android/ssv 확인, 2026-06-02):
 *  - SSV 콜백 query string 의 **마지막 두 파라미터는 항상 `signature`, `key_id`** (그 순서).
 *  - 서명 대상(content) = query string 에서 `&signature=` **직전까지의 raw 부분문자열**.
 *    원본 그대로(파라미터 순서·인코딩 변경 금지)의 UTF-8 바이트.
 *  - 서명 = base64(url-safe 가능) 인코딩된 DER ECDSA. 알고리즘 = SHA256withECDSA (P-256).
 *  - 공개키 = AdMob key server(`verifier-keys.json`)의 `keys[].{keyId, pem}`. **24h 이내 캐시**(주기적 rotate).
 *
 * 본 클래스는 **순수 서명 검증 + 키 캐시**만 담당한다. 콜백 파싱/dedup/audit 은
 * [AdSsvCallbackController]. 보상 지급 정책(SSV 를 authoritative grant 로 승격)은
 * 클라이언트 경로(claimAdReward)와의 이중지급 회피를 위한 **별도 product 결정** 필요 — 본 cycle 범위 밖.
 *
 * ⚠️ 인간/실트래픽 미검증: 실제 AdMob 콜백 샘플로 G2(외부 secret 실동작)·G3 통과 전엔 '완료' 아님
 * (인간 테스트 게이트). timestamp 단위(μs) 등 경험적 튜닝 항목은 실콜백 확인 후 강화.
 */
@Component
class AdSsvSignatureVerifier(
    private val objectMapper: ObjectMapper,
    @Value("\${admob.ssv.public-key-url:https://www.gstatic.com/admob/reward/verifier-keys.json}")
    private val keyServerUrl: String,
) {
    companion object {
        private val log = LoggerFactory.getLogger(AdSsvSignatureVerifier::class.java)
        private val KEY_TTL: Duration = Duration.ofHours(24)
        private val FETCH_TIMEOUT: Duration = Duration.ofSeconds(5)
    }

    @Volatile
    private var keyCache: Map<Long, PublicKey> = emptyMap()

    @Volatile
    private var fetchedAt: Instant? = null

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(FETCH_TIMEOUT).build()
    }

    /**
     * SSV 콜백 검증. content 는 raw query string 의 `&signature=` 직전까지 부분문자열.
     * @return true = 서명 유효, false = 무효 / 키 조회 실패 / 파싱 실패.
     */
    fun verify(
        content: String,
        signatureBase64: String,
        keyId: Long,
    ): Boolean {
        val key = publicKey(keyId)
        if (key == null) {
            log.warn("ad.ssv.verify — keyId={} 공개키 조회 실패 (key server 응답/rotate 확인)", keyId)
            return false
        }
        return verifyWithKey(content, signatureBase64, key)
    }

    /** 키 주입형 검증 — 단위테스트가 HTTP 없이 self-signed 키로 검증 가능하게 분리. */
    internal fun verifyWithKey(
        content: String,
        signatureBase64: String,
        publicKey: PublicKey,
    ): Boolean =
        try {
            val sigBytes = decodeBase64(signatureBase64)
            val verifier = Signature.getInstance("SHA256withECDSA")
            verifier.initVerify(publicKey)
            verifier.update(content.toByteArray(StandardCharsets.UTF_8))
            verifier.verify(sigBytes)
        } catch (e: Exception) {
            log.warn("ad.ssv.verify — 서명 검증 예외: {}", e.message)
            false
        }

    private fun publicKey(keyId: Long): PublicKey? {
        if (isFresh() && keyCache.containsKey(keyId)) return keyCache[keyId]
        synchronized(this) {
            if (isFresh() && keyCache.containsKey(keyId)) return keyCache[keyId]
            try {
                keyCache = fetchKeys()
                fetchedAt = Instant.now()
            } catch (e: Exception) {
                log.warn("ad.ssv.keys — key server fetch 실패 (기존 캐시 유지): {}", e.message)
            }
        }
        return keyCache[keyId]
    }

    private fun isFresh(): Boolean {
        val at = fetchedAt ?: return false
        return Duration.between(at, Instant.now()) < KEY_TTL
    }

    private fun fetchKeys(): Map<Long, PublicKey> {
        val request =
            HttpRequest
                .newBuilder(URI.create(keyServerUrl))
                .timeout(FETCH_TIMEOUT)
                .GET()
                .build()
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("key server status=${response.statusCode()}")
        }
        val root = objectMapper.readTree(response.body())
        val keysNode = root.get("keys") ?: throw IllegalStateException("verifier-keys.json 'keys' 누락")
        val result = HashMap<Long, PublicKey>()
        for (node in keysNode) {
            val keyId = node.get("keyId")?.asLong() ?: continue
            val pem = node.get("pem")?.asText() ?: continue
            runCatching { result[keyId] = parsePem(pem) }
                .onFailure { log.warn("ad.ssv.keys — keyId={} pem 파싱 실패: {}", keyId, it.message) }
        }
        log.info("ad.ssv.keys — {} 개 공개키 로드", result.size)
        return result
    }

    private fun parsePem(pem: String): PublicKey {
        val base64 =
            pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        val der = Base64.getDecoder().decode(base64)
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))
    }

    /** base64 / base64url + padding 누락 모두 허용. */
    private fun decodeBase64(value: String): ByteArray {
        var normalized = value.trim().replace('-', '+').replace('_', '/')
        val pad = (4 - normalized.length % 4) % 4
        normalized += "=".repeat(pad)
        return Base64.getDecoder().decode(normalized)
    }
}
