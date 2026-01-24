package com.example.marketplace.member.dto

import jakarta.validation.constraints.NotBlank

data class LogoutRequest(
    @field:NotBlank(message = "Access token is required")
    val accessToken: String,
    val refreshToken: String? = null
)
