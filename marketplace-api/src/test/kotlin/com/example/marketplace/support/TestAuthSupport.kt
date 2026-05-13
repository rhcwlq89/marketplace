package com.example.marketplace.support

import com.example.marketplace.member.AuthService
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.SignupRequest

data class AuthenticatedMember(
    val memberId: Long,
    val bearerToken: String,
)

object TestAuthSupport {

    const val DEFAULT_PASSWORD = "password123"

    fun signupAndLogin(
        authService: AuthService,
        memberJpaRepository: MemberJpaRepository,
        email: String,
        role: String,
        businessNumber: String? = if (role == "SELLER") "123-45-67890" else null,
        name: String = "Test ${role.lowercase()}",
    ): AuthenticatedMember {
        authService.signup(
            SignupRequest(
                email = email,
                password = DEFAULT_PASSWORD,
                name = name,
                role = role,
                businessNumber = businessNumber,
            )
        )
        val token = authService.login(LoginRequest(email, DEFAULT_PASSWORD)).accessToken
        val memberId = memberJpaRepository.findByEmail(email).get().id!!
        return AuthenticatedMember(memberId, "Bearer $token")
    }
}
