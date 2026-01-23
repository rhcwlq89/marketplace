package com.example.marketplace.security

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "jwt")
data class JwtProperties(
    val secret: String = "",
    val accessTokenValidity: Long = 3600000,    // 1 hour in milliseconds
    val refreshTokenValidity: Long = 604800000  // 7 days in milliseconds
)
