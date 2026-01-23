package com.example.marketplace.member.dto

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank

data class SignupRequest(
    @field:Email
    @field:NotBlank
    val email: String,

    @field:NotBlank
    val password: String,

    val name: String? = null,
    val phone: String? = null,
    val role: String = "BUYER",
    val businessNumber: String? = null
)
