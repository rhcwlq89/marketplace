package com.example.marketplace.security

import org.springframework.security.oauth2.jose.jws.MacAlgorithm
import org.springframework.security.oauth2.jwt.JwsHeader
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.oauth2.jwt.JwtException
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class JwtTokenProvider(
    private val jwtProperties: JwtProperties,
    private val jwtEncoder: JwtEncoder,
    private val jwtDecoder: JwtDecoder,
) {

    val accessTokenValidityInSeconds: Long
        get() = jwtProperties.accessTokenValidity / 1000

    val refreshTokenValidityInSeconds: Long
        get() = jwtProperties.refreshTokenValidity / 1000

    fun createAccessToken(userId: Long, email: String, role: String): String =
        encode(userId, email, role, jwtProperties.accessTokenValidity)

    fun createRefreshToken(userId: Long, email: String, role: String): String =
        encode(userId, email, role, jwtProperties.refreshTokenValidity)

    fun validateToken(token: String): Boolean = try {
        jwtDecoder.decode(token)
        true
    } catch (ex: JwtException) {
        false
    }

    fun getUserId(token: String): Long = jwtDecoder.decode(token).subject.toLong()

    private fun encode(userId: Long, email: String, role: String, validityMillis: Long): String {
        val now = Instant.now()
        val claims = JwtClaimsSet.builder()
            .subject(userId.toString())
            .issuedAt(now)
            .expiresAt(now.plusMillis(validityMillis))
            .claim("email", email)
            .claim("role", role)
            .build()
        val header = JwsHeader.with(MacAlgorithm.HS256).build()
        return jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).tokenValue
    }
}
