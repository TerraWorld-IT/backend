package com.terraworld.api.reward

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyFactory
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.Base64

/**
 * AdMob SSV(Server-Side Verification) 서명검증기. (fullstack-ultraplan WP-3, 2026-06-04 신설)
 *
 * Google AdMob rewarded ad 의 SSV callback 은 GET query string 에 ECDSA P-256 SHA-256 서명을
 * 포함한다. 본 verifier 는 `reward.ad.ssv.public-key-url` 의 공개키 set 을 24h 캐시하고 key_id 매칭
 * 후 서명을 검증한다 — 신규 의존성 없이 JDK `java.security` + `java.net.http` 만 사용.
 *
 * 검증 대상(content) = query string 에서 "&signature=" 직전까지의 raw 문자열
 * (Google 공식 spec: 마지막 두 param signature/key_id 를 제외한 앞부분).
 * 서명 본체 = signature param(base64url, DER ECDSA). 공개키 = pem/base64(X.509 SubjectPublicKeyInfo).
 *
 * ⚠️ 실 SSV 트래픽 미검증(.env-ready) — `ADMOB_SSV_PUBLIC_KEY_URL`(기본 Google 고정 URL) 주입 +
 * 실 광고 노출 후 1회 실트래픽 검증 필요(WP-3 G3 게이트).
 */
@Component
class AdSsvSignatureVerifier(
    @Value("\${reward.ad.ssv.public-key-url:https://gstatic.com/admob/reward/verifier-keys.json}")
    private val publicKeyUrl: String,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1)
            .build()

    @Volatile
    private var cache: Map<Long, ECPublicKey> = emptyMap()

    @Volatile
    private var cacheLoadedAtMs: Long = 0L

    // Codex 보안 MED (2026-06-04): 무인증 SSV endpoint 의 unknown key_id 반복 요청이 매번 원격
    // key fetch 를 유발하는 DoS 완화 — cache miss 시 refresh 를 negative-cache 윈도우(60s)로 제한.
    @Volatile
    private var lastMissRefreshAtMs: Long = 0L

    private val cacheTtlMs = Duration.ofHours(24).toMillis()
    private val missRefreshWindowMs = Duration.ofSeconds(60).toMillis()

    companion object {
        /**
         * 공개키 JSON 파싱부터 실제 ECDSA 검증까지 네트워크 없이 검증하는 테스트 전용 팩토리.
         *
         * @Component 는 단일 생성자만 두어야 Spring 이 주입 대상을 모호함 없이 선택한다
         * (생성자를 둘 두면 @Autowired 부재 시 no-arg 폴백으로 부팅이 실패). 테스트용
         * 대체 생성 경로는 이 팩토리로 분리한다.
         */
        internal fun forTest(
            objectMapper: ObjectMapper,
            keySetJson: String,
        ): AdSsvSignatureVerifier =
            AdSsvSignatureVerifier("https://test.invalid/admob-keys.json", objectMapper).apply {
                cache = parseKeys(keySetJson)
                cacheLoadedAtMs = System.currentTimeMillis()
                lastMissRefreshAtMs = cacheLoadedAtMs
            }
    }

    /**
     * @return true = 서명 유효. key set fetch 실패 / key_id 미발견 / 서명 불일치 시 false.
     */
    fun verify(
        contentToVerify: String,
        signatureBase64Url: String,
        keyId: Long,
    ): Boolean {
        val key =
            resolveKey(keyId) ?: run {
                log.warn("ad.ssv.key-not-found keyId={}", keyId)
                return false
            }
        return try {
            val sigBytes = Base64.getUrlDecoder().decode(padBase64Url(signatureBase64Url))
            val sig = Signature.getInstance("SHA256withECDSA")
            sig.initVerify(key)
            sig.update(contentToVerify.toByteArray(Charsets.UTF_8))
            sig.verify(sigBytes)
        } catch (e: Exception) {
            log.warn("ad.ssv.verify-error keyId={} msg={}", keyId, e.message)
            false
        }
    }

    private fun resolveKey(keyId: Long): ECPublicKey? {
        val now = System.currentTimeMillis()
        if (cache.isEmpty() || now - cacheLoadedAtMs > cacheTtlMs) {
            // TTL 만료 뒤 갱신 실패 시 과거 키를 계속 신뢰하지 않고 fail closed 한다.
            if (!refreshKeys()) return null
        }
        cache[keyId]?.let { return it }
        // key_id 미스 — 키 rotation 대응 refresh, 단 negative-cache 윈도우(60s)로 DoS 완화.
        // 무인증 endpoint 라 임의 key_id 폭주 시에도 원격 fetch 가 분당 1회로 제한됨.
        if (now - lastMissRefreshAtMs > missRefreshWindowMs) {
            lastMissRefreshAtMs = now
            if (!refreshKeys()) return null
            return cache[keyId]
        }
        return null
    }

    @Synchronized
    private fun refreshKeys(): Boolean =
        try {
            val req =
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(publicKeyUrl))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build()
            val res = httpClient.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) {
                log.warn("ad.ssv.keys-fetch status={}", res.statusCode())
                false
            } else {
                val parsed = parseKeys(res.body())
                if (parsed.isEmpty()) {
                    false
                } else {
                    cache = parsed
                    cacheLoadedAtMs = System.currentTimeMillis()
                    true
                }
            }
        } catch (e: Exception) {
            log.warn("ad.ssv.keys-fetch-error msg={}", e.message)
            false
        }

    private fun parseKeys(json: String): Map<Long, ECPublicKey> {
        val root = objectMapper.readTree(json)
        val keysNode = root.get("keys") ?: return emptyMap()
        val map = HashMap<Long, ECPublicKey>()
        val factory = KeyFactory.getInstance("EC")
        for (node in keysNode) {
            val keyId = node.get("keyId")?.asLong() ?: continue
            // pem(헤더 포함) 또는 base64(DER) — 둘 다 strip 후 X.509 디코드.
            val raw = node.get("pem")?.asText() ?: node.get("base64")?.asText() ?: continue
            try {
                val der = pemToDer(raw)
                val pub = factory.generatePublic(X509EncodedKeySpec(der)) as ECPublicKey
                map[keyId] = pub
            } catch (e: Exception) {
                log.warn("ad.ssv.key-parse-error keyId={} msg={}", keyId, e.message)
            }
        }
        return map
    }

    private fun pemToDer(pem: String): ByteArray {
        val body =
            pem
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("\\s".toRegex(), "")
        return Base64.getDecoder().decode(body)
    }

    private fun padBase64Url(s: String): String {
        val rem = s.length % 4
        return if (rem == 0) s else s + "=".repeat(4 - rem)
    }
}
