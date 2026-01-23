package com.example.marketplace.member.dto

data class TokenResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long
)
