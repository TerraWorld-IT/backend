package com.terraworld.security

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import com.sun.net.httpserver.HttpServer
import com.terraworld.domain.user.UserRole
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64
import java.util.Date
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * End-to-end tests for [JwtTokenProvider]. Uses a real in-process
 * HttpServer to serve JWKS + a real RSA keypair so the full
 * sign-and-verify path is exercised. Covers SEC-001/002/013/ARCH-005.
 */
class JwtTokenProviderTest {

    private lateinit var server: HttpServer
    private lateinit var keyPair: KeyPair
    private lateinit var kid: String
    private lateinit var jwksJson: String
    private lateinit var provider: JwtTokenProvider

    /** Tracks how many times the JWKS endpoint was hit (DoS rate-limit checks). */
    private val jwksRequests = AtomicInteger(0)

    /** Lets individual tests override the response served by the JWKS endpoint. */
    @Volatile
    private var jwksResponseOverride: ((HttpExchange) -> Unit)? = null

    @BeforeEach
    fun setUp() {
        keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        kid = "test-${UUID.randomUUID()}".take(40)
        jwksJson = buildJwksJson(kid, keyPair.public as RSAPublicKey)

        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext(
            "/jwks",
            HttpHandler { exchange ->
                jwksRequests.incrementAndGet()
                val override = jwksResponseOverride
                if (override != null) {
                    override(exchange)
                    return@HttpHandler
                }
                val body = jwksJson.toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.responseHeaders.add("Content-Length", body.size.toString())
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            },
        )
        server.start()

        val url = "http://127.0.0.1:${server.address.port}/jwks"
        provider = JwtTokenProvider(
            jwksUrl = url,
            expectedIssuer = "terraworld",
            expectedAudience = "terraworld-api",
            refreshCooldownMs = 30_000L,
            negativeCacheTtlMs = 60_000L,
            maxJwksBytes = 65_536,
            objectMapper = ObjectMapper(),
        )
        provider.warmup()
        jwksRequests.set(0)
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
        jwksResponseOverride = null
    }

    @Test
    fun `valid token verifies and yields claims`() {
        val token = signToken(
            sub = "cld-user-1",
            email = "user@example.com",
            role = "USER",
            issuer = "terraworld",
            audience = "terraworld-api",
        )

        assertTrue(provider.validateToken(token))
        assertEquals("cld-user-1", provider.getUserIdFromToken(token))
        assertEquals("user@example.com", provider.getEmailFromToken(token))
        assertEquals(UserRole.USER, provider.getRoleFromToken(token))
    }

    @Test
    fun `token with wrong issuer is rejected`() {
        val token = signToken(issuer = "other-app")
        assertFalse(provider.validateToken(token))
    }

    @Test
    fun `token with wrong audience is rejected`() {
        val token = signToken(audience = "other-api")
        assertFalse(provider.validateToken(token))
    }

    @Test
    fun `token with no exp is rejected`() {
        val token = Jwts.builder()
            .header().keyId(kid).and()
            .issuer("terraworld")
            .audience().add("terraworld-api").and()
            .subject("cld-user-1")
            .claim("email", "user@example.com")
            .claim("role", "USER")
            .issuedAt(Date())
            // no expiration
            .signWith(keyPair.private, Jwts.SIG.RS256)
            .compact()

        assertFalse(provider.validateToken(token))
    }

    @Test
    fun `expired token is rejected`() {
        val token = signToken(expirationOffsetMs = -60_000L)
        assertFalse(provider.validateToken(token))
    }

    @Test
    fun `getEmailFromToken returns null for blank email claim`() {
        val token = signToken(email = " ")
        assertEquals(null, provider.getEmailFromToken(token))
    }

    @Test
    fun `getRoleFromToken falls back to USER for unknown role string`() {
        val token = signToken(role = "SUPERUSER")
        assertEquals(UserRole.USER, provider.getRoleFromToken(token))
    }

    @Test
    fun `unknown kid is negatively cached and does not refresh on every request`() {
        // Use a fresh provider with cooldown=0 so the first unknown-kid call
        // is allowed to refresh — we want to verify the NEGATIVE CACHE blocks
        // subsequent calls, not the cooldown gate (cooldown is exercised in
        // a separate test below).
        val isolated = JwtTokenProvider(
            jwksUrl = "http://127.0.0.1:${server.address.port}/jwks",
            expectedIssuer = "terraworld",
            expectedAudience = "terraworld-api",
            refreshCooldownMs = 0L,
            negativeCacheTtlMs = 60_000L,
            maxJwksBytes = 65_536,
            objectMapper = ObjectMapper(),
        )
        isolated.warmup()
        jwksRequests.set(0)

        val rogueKid = "rogue-${UUID.randomUUID()}".take(40)
        val rogueKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rogueToken = Jwts.builder()
            .header().keyId(rogueKid).and()
            .issuer("terraworld")
            .audience().add("terraworld-api").and()
            .subject("cld-attacker")
            .claim("email", "attacker@example.com")
            .claim("role", "USER")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(rogueKey.private, Jwts.SIG.RS256)
            .compact()

        // First request triggers a refresh attempt — JWKS endpoint hit once.
        assertFalse(isolated.validateToken(rogueToken))
        val afterFirst = jwksRequests.get()
        assertEquals(1, afterFirst, "first unknown-kid hit triggers exactly one refresh")

        // SEC-002: subsequent requests with the same kid must NOT refresh.
        repeat(20) {
            assertFalse(isolated.validateToken(rogueToken))
        }
        assertEquals(
            afterFirst,
            jwksRequests.get(),
            "negative cache must prevent further refresh attempts for the same kid",
        )
    }

    @Test
    fun `negative cache expires after TTL and allows another refresh attempt`() {
        // 100ms negative cache so the test runs quickly. Cooldown=0 to
        // isolate the negative cache pathway from the rate-limit gate.
        val isolated = JwtTokenProvider(
            jwksUrl = "http://127.0.0.1:${server.address.port}/jwks",
            expectedIssuer = "terraworld",
            expectedAudience = "terraworld-api",
            refreshCooldownMs = 0L,
            negativeCacheTtlMs = 100L,
            maxJwksBytes = 65_536,
            objectMapper = ObjectMapper(),
        )
        isolated.warmup()
        jwksRequests.set(0)

        val rogueKid = "rogue-${UUID.randomUUID()}".take(40)
        val rogueKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rogueToken = Jwts.builder()
            .header().keyId(rogueKid).and()
            .issuer("terraworld")
            .audience().add("terraworld-api").and()
            .subject("cld-attacker")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(rogueKey.private, Jwts.SIG.RS256)
            .compact()

        // First call refreshes once.
        assertFalse(isolated.validateToken(rogueToken))
        assertEquals(1, jwksRequests.get())

        // Within TTL: negatively cached, no extra refresh.
        assertFalse(isolated.validateToken(rogueToken))
        assertEquals(1, jwksRequests.get())

        // Wait past the TTL window.
        Thread.sleep(150)

        // After TTL: negative cache entry expires → refresh attempted again.
        assertFalse(isolated.validateToken(rogueToken))
        assertEquals(2, jwksRequests.get(), "negative cache entry must expire after TTL")
    }

    @Test
    fun `refresh cooldown blocks rapid refresh after a successful one`() {
        // The default `provider` has cooldown=30s. After warmup populated the
        // cache, even an unknown kid should NOT trigger an immediate refresh.
        val rogueKid = "rogue-${UUID.randomUUID()}".take(40)
        val rogueKey = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val rogueToken = Jwts.builder()
            .header().keyId(rogueKid).and()
            .issuer("terraworld")
            .audience().add("terraworld-api").and()
            .subject("cld-attacker")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(rogueKey.private, Jwts.SIG.RS256)
            .compact()

        repeat(10) {
            assertFalse(provider.validateToken(rogueToken))
        }
        assertEquals(0, jwksRequests.get(), "refresh cooldown must block all 10 attempts")
    }

    @Test
    fun `kid with implausible characters is rejected without hitting JWKS`() {
        val token = Jwts.builder()
            .header().keyId("../etc/passwd").and()
            .issuer("terraworld")
            .audience().add("terraworld-api").and()
            .subject("cld-attacker")
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 60_000))
            .signWith(keyPair.private, Jwts.SIG.RS256)
            .compact()

        val before = jwksRequests.get()
        assertFalse(provider.validateToken(token))
        assertEquals(before, jwksRequests.get(), "implausible kid must not trigger JWKS refresh")
    }

    @Test
    fun `JWKS response over size limit is rejected at warmup`() {
        // Replace the response with an oversized body and rebuild the provider.
        jwksResponseOverride = { exchange ->
            val body = "x".repeat(200_000).toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val freshProvider = JwtTokenProvider(
            jwksUrl = "http://127.0.0.1:${server.address.port}/jwks",
            expectedIssuer = "terraworld",
            expectedAudience = "terraworld-api",
            refreshCooldownMs = 30_000L,
            negativeCacheTtlMs = 60_000L,
            maxJwksBytes = 65_536,
            objectMapper = ObjectMapper(),
        )
        // Warmup catches the failure and logs but does not throw — verify
        // that the empty cache then surfaces as token rejection.
        freshProvider.warmup()
        val token = signToken()
        assertFalse(freshProvider.validateToken(token))
    }

    @Test
    fun `JWKS response with wrong content-type is rejected`() {
        jwksResponseOverride = { exchange ->
            val body = "<html>not json</html>".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/html")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        val freshProvider = JwtTokenProvider(
            jwksUrl = "http://127.0.0.1:${server.address.port}/jwks",
            expectedIssuer = "terraworld",
            expectedAudience = "terraworld-api",
            refreshCooldownMs = 30_000L,
            negativeCacheTtlMs = 60_000L,
            maxJwksBytes = 65_536,
            objectMapper = ObjectMapper(),
        )
        freshProvider.warmup()
        val token = signToken()
        assertFalse(freshProvider.validateToken(token))
    }

    // ---- helpers ----

    private fun signToken(
        sub: String = "cld-user-1",
        email: String = "user@example.com",
        role: String = "USER",
        issuer: String = "terraworld",
        audience: String = "terraworld-api",
        expirationOffsetMs: Long = 60_000L,
    ): String =
        Jwts.builder()
            .header().keyId(kid).and()
            .issuer(issuer)
            .audience().add(audience).and()
            .subject(sub)
            .claim("email", email)
            .claim("role", role)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expirationOffsetMs))
            .signWith(keyPair.private, Jwts.SIG.RS256)
            .compact()

    private fun buildJwksJson(kid: String, key: RSAPublicKey): String {
        val n = Base64.getUrlEncoder().withoutPadding().encodeToString(stripLeadingZero(key.modulus.toByteArray()))
        val e = Base64.getUrlEncoder().withoutPadding().encodeToString(stripLeadingZero(key.publicExponent.toByteArray()))
        return """{"keys":[{"kty":"RSA","kid":"$kid","use":"sig","alg":"RS256","n":"$n","e":"$e"}]}"""
    }

    private fun stripLeadingZero(bytes: ByteArray): ByteArray =
        if (bytes.isNotEmpty() && bytes[0] == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
}
