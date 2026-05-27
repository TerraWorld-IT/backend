package com.terraworld.security

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.terraworld.domain.user.UserRole
import io.jsonwebtoken.Claims
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigInteger
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.Key
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.RSAPublicKeySpec
import java.time.Duration
import java.util.Base64
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Validates RS256 JWTs issued by better-auth (Nuxt/Nitro side).
 *
 * This backend NO LONGER issues tokens. better-auth owns the sign-in flow
 * and mints JWTs signed with keys stored in `auth.jwks`. Public keys are
 * served at `${auth.jwks.url}` and cached here.
 *
 * Claim contract (enforced by `jwt()` definePayload on the Nitro side):
 *   - iss   : "terraworld" (required — rejected otherwise)
 *   - aud   : "terraworld-api" (required — rejected otherwise)
 *   - exp   : 5 minutes from issue (required — rejected if absent). Short
 *             TTL bounds the role-staleness window after admin demotion.
 *   - sub   : user id (CUID from auth.user)
 *   - email : email address (required non-blank by the filter)
 *   - role  : "USER" | "ADMIN" (mirrored from public.users.role)
 *
 * Security defenses:
 *   - Strict kid regex ([A-Za-z0-9_-]{1,64}) blocks implausible headers.
 *   - Negative cache for unknown kids (60s) prevents attacker-forced refresh.
 *   - Global refresh cooldown (30s) rate-limits JWKS origin pressure.
 *   - In-flight dedup — only one concurrent refresh, waiters reuse its result.
 *   - Atomic cache replacement — concurrent reads never see an empty map.
 *   - Content-Length and content-type guards on the JWKS response.
 *   - Fail-on-first-request when JWKS returns zero RSA keys. Warmup is
 *     best-effort (the frontend may not be up yet at backend startup), so
 *     it logs and continues; the empty-cache check fires the first time
 *     the cache is repopulated and still returns nothing.
 */
@Component
class JwtTokenProvider(
    @Value("\${auth.jwks.url}") private val jwksUrl: String,
    @Value("\${auth.jwt.issuer:terraworld}") private val expectedIssuer: String,
    @Value("\${auth.jwt.audience:terraworld-api}") private val expectedAudience: String,
    @Value("\${auth.jwks.refresh-cooldown-ms:30000}") private val refreshCooldownMs: Long,
    @Value("\${auth.jwks.negative-cache-ttl-ms:60000}") private val negativeCacheTtlMs: Long,
    @Value("\${auth.jwks.max-response-bytes:65536}") private val maxJwksBytes: Int,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(JwtTokenProvider::class.java)
    private val httpClient: HttpClient =
        HttpClient
            .newBuilder()
            // HTTP/1.1 강제 — Nuxt/Nitro dev server 가 HTTP/2 upgrade 미지원이라 Java 11+
            // default HTTP/2 client 가 "header parser received no bytes" 로 fail. dev e2e
            // 환경의 JWKS fetch 안정성 ↑. production HTTPS 도 H2 명시 negotiation 가능.
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(5))
            .build()

    /**
     * Immutable map swapped atomically by refreshJwks. Reads never see a
     * half-populated map — unlike the prior ConcurrentHashMap.clear+putAll
     * which produced empty-map windows under concurrent refresh.
     */
    @Volatile
    private var keyCache: Map<String, PublicKey> = emptyMap()

    /** Unknown kids observed recently. Value = monotonic epoch ms of last miss. */
    private val negativeKidCache = ConcurrentHashMap<String, Long>()

    /** Last successful refresh timestamp (monotonic epoch ms). */
    private val lastRefreshMs = AtomicLong(0)

    /**
     * Single in-flight refresh future. Waiters wait on the same future
     * instead of each hitting the JWKS origin.
     */
    private val refreshInFlight = AtomicReference<CompletableFuture<Unit>?>(null)

    @PostConstruct
    fun warmup() {
        runCatching { refreshJwksSync(throwOnEmpty = false) }
            .onFailure {
                log.warn(
                    "[auth] initial JWKS fetch failed — will retry on first request: {}",
                    it.message,
                )
            }
    }

    fun validateToken(token: String): Boolean =
        try {
            parseClaims(token)
            true
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }

    fun getUserIdFromToken(token: String): String = parseClaims(token).subject

    /** Returns null (not "") when the email claim is absent — filter must reject. */
    fun getEmailFromToken(token: String): String? = parseClaims(token).get("email", String::class.java)?.takeIf { it.isNotBlank() }

    fun getRoleFromToken(token: String): UserRole =
        parseClaims(token)
            .get("role", String::class.java)
            ?.let { runCatching { UserRole.valueOf(it) }.getOrNull() }
            ?: UserRole.USER

    private fun parseClaims(token: String): Claims {
        val claims =
            Jwts
                .parser()
                .requireIssuer(expectedIssuer)
                .requireAudience(expectedAudience)
                .clockSkewSeconds(30)
                .keyLocator { header ->
                    val kid =
                        header["kid"]?.toString()?.takeIf { it.isNotBlank() }
                            ?: throw JwtException("JWT header missing 'kid'")
                    if (!KID_REGEX.matches(kid)) {
                        throw JwtException("JWT 'kid' rejected as implausible: ${kid.take(16)}")
                    }
                    resolveKey(kid)
                }.build()
                .parseSignedClaims(token)
                .payload

        if (claims.expiration == null) {
            throw JwtException("JWT missing required 'exp' claim")
        }
        return claims
    }

    private fun resolveKey(kid: String): Key {
        keyCache[kid]?.let { return it }

        val now = System.currentTimeMillis()
        val lastMiss = negativeKidCache[kid]
        if (lastMiss != null && (now - lastMiss) < negativeCacheTtlMs) {
            throw JwtException("Unknown kid (negatively cached)")
        }

        if ((now - lastRefreshMs.get()) < refreshCooldownMs && keyCache.isNotEmpty()) {
            negativeKidCache[kid] = now
            throw JwtException("Unknown kid (refresh cooldown)")
        }

        refreshJwks()

        return keyCache[kid] ?: run {
            negativeKidCache[kid] = now
            throw JwtException("Unknown kid (not found in JWKS)")
        }
    }

    /**
     * Coordinated refresh: the first caller runs the sync fetch, concurrent
     * callers wait on the same future. If the winner's fetch fails, waiters
     * see the same exception (but wrapped in JwtException above).
     */
    private fun refreshJwks() {
        while (true) {
            val existing = refreshInFlight.get()
            if (existing != null && !existing.isDone) {
                try {
                    existing.get(10, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    // fall through and let the caller re-check cache
                }
                return
            }
            val fresh = CompletableFuture<Unit>()
            if (refreshInFlight.compareAndSet(existing, fresh)) {
                try {
                    refreshJwksSync(throwOnEmpty = keyCache.isEmpty())
                    fresh.complete(Unit)
                } catch (e: Throwable) {
                    fresh.completeExceptionally(e)
                } finally {
                    refreshInFlight.compareAndSet(fresh, null)
                }
                return
            }
            // Another thread won the CAS — loop and wait on their future.
        }
    }

    private fun refreshJwksSync(throwOnEmpty: Boolean) {
        lastRefreshMs.set(System.currentTimeMillis())
        val response =
            httpClient.send(
                HttpRequest
                    .newBuilder()
                    .uri(URI.create(jwksUrl))
                    .timeout(Duration.ofSeconds(5))
                    .header("Accept", "application/json")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        if (response.statusCode() !in 200..299) {
            throw IllegalStateException("JWKS fetch failed: HTTP ${response.statusCode()}")
        }

        val declaredLength =
            response
                .headers()
                .firstValue("content-length")
                .orElse("0")
                .toLongOrNull() ?: 0
        if (declaredLength > maxJwksBytes) {
            throw IllegalStateException("JWKS response too large: declared $declaredLength bytes")
        }
        val body = response.body()
        if (body.length > maxJwksBytes) {
            throw IllegalStateException("JWKS response too large: ${body.length} bytes")
        }
        val contentType = response.headers().firstValue("content-type").orElse("")
        if (!contentType.contains("json", ignoreCase = true)) {
            throw IllegalStateException("JWKS content-type not JSON: $contentType")
        }

        val keys = objectMapper.readTree(body).path("keys")
        if (!keys.isArray) throw IllegalStateException("JWKS malformed: no 'keys' array")

        val fresh = HashMap<String, PublicKey>()
        keys.forEach { jwk ->
            runCatching { parseJwk(jwk) }
                .onSuccess { (kid, key) -> fresh[kid] = key }
                .onFailure { log.debug("[auth] skipping unparseable JWK: {}", it.message) }
        }

        if (fresh.isEmpty() && throwOnEmpty) {
            throw IllegalStateException(
                "JWKS refresh yielded zero usable keys. If better-auth switched from RS256, " +
                    "update JwtTokenProvider.parseJwk to handle the new kty.",
            )
        }

        keyCache = Collections.unmodifiableMap(fresh)
        // Any key we previously marked unknown might now be valid; clear the
        // negative cache on every successful refresh.
        negativeKidCache.clear()
        log.info("[auth] JWKS refreshed: {} keys", fresh.size)
    }

    private fun parseJwk(jwk: JsonNode): Pair<String, PublicKey> {
        val kid = jwk.path("kid").asText().ifBlank { throw IllegalArgumentException("missing kid") }
        val kty = jwk.path("kty").asText()
        return when (kty) {
            "RSA" -> {
                val n = BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("n").asText()))
                val e = BigInteger(1, Base64.getUrlDecoder().decode(jwk.path("e").asText()))
                val key = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(n, e))
                kid to key
            }
            else -> throw IllegalArgumentException("unsupported kty: $kty")
        }
    }

    companion object {
        private val KID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    }
}
