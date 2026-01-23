package com.example.marketplace.security

import io.jsonwebtoken.*
import io.jsonwebtoken.security.Keys
import jakarta.annotation.PostConstruct
import org.springframework.stereotype.Component
import java.security.Key
import java.util.*

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties
) {
    private lateinit var key: Key

    val accessTokenValidityInSeconds: Long
        get() = jwtProperties.accessTokenValidity / 1000

    val refreshTokenValidityInSeconds: Long
        get() = jwtProperties.refreshTokenValidity / 1000

    @PostConstruct
    fun init() {
        key = if (jwtProperties.secret.isNotBlank() && jwtProperties.secret.toByteArray().size >= 32) {
            Keys.hmacShaKeyFor(jwtProperties.secret.toByteArray())
        } else {
            Keys.secretKeyFor(SignatureAlgorithm.HS256)
        }
    }

    fun createAccessToken(userId: Long, email: String, role: String): String {
        val now = Date()
        val expiry = Date(now.time + jwtProperties.accessTokenValidity)
        return Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(now)
            .setExpiration(expiry)
            .claim("email", email)
            .claim("role", role)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun createRefreshToken(userId: Long, email: String, role: String): String {
        val now = Date()
        val expiry = Date(now.time + jwtProperties.refreshTokenValidity)
        return Jwts.builder()
            .setSubject(userId.toString())
            .setIssuedAt(now)
            .setExpiration(expiry)
            .claim("email", email)
            .claim("role", role)
            .signWith(key, SignatureAlgorithm.HS256)
            .compact()
    }

    fun validateToken(token: String): Boolean {
        return try {
            Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token)
            true
        } catch (ex: JwtException) {
            false
        }
    }

    fun getUserId(token: String): Long {
        val claims = Jwts.parserBuilder().setSigningKey(key).build().parseClaimsJws(token).body
        return claims.subject.toLong()
    }

    fun getKey(): Key = key
}
