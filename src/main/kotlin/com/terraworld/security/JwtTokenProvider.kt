package com.terraworld.security

import io.jsonwebtoken.*
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.util.*
import javax.crypto.SecretKey

@Component
class JwtTokenProvider(
    @Value("\${jwt.secret}") private val secret: String,
    @Value("\${jwt.access-expiration}") private val accessExpiration: Long,
    @Value("\${jwt.refresh-expiration}") private val refreshExpiration: Long,
) {
    private val key: SecretKey by lazy { Keys.hmacShaKeyFor(secret.toByteArray()) }

    fun generateAccessToken(userId: Long, email: String): String =
        generateToken(userId, email, accessExpiration)

    fun generateRefreshToken(userId: Long, email: String): String =
        generateToken(userId, email, refreshExpiration)

    private fun generateToken(userId: Long, email: String, expiration: Long): String =
        Jwts.builder()
            .subject(userId.toString())
            .claim("email", email)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + expiration))
            .signWith(key)
            .compact()

    fun getUserIdFromToken(token: String): Long =
        parseClaims(token).subject.toLong()

    fun validateToken(token: String): Boolean =
        try {
            parseClaims(token)
            true
        } catch (e: JwtException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }

    private fun parseClaims(token: String): Claims =
        Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
}
