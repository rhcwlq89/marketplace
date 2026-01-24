package com.example.marketplace.member

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.RefreshRequest
import com.example.marketplace.member.dto.SignupRequest
import com.example.marketplace.member.dto.TokenResponse
import com.example.marketplace.security.JwtTokenProvider
import com.example.marketplace.security.TokenBlacklistService
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class AuthService(
    private val memberJpaRepository: MemberJpaRepository,
    private val passwordEncoder: PasswordEncoder,
    private val jwtTokenProvider: JwtTokenProvider,
    private val tokenBlacklistService: TokenBlacklistService
) {

    @Transactional
    fun signup(req: SignupRequest) {
        if (memberJpaRepository.existsByEmail(req.email)) {
            throw BusinessException(ErrorCode.EMAIL_ALREADY_EXISTS)
        }

        val role = Role.valueOf(req.role)
        if (role == Role.SELLER && req.businessNumber.isNullOrBlank()) {
            throw BusinessException(ErrorCode.BUSINESS_NUMBER_REQUIRED)
        }

        val member = Member(
            email = req.email,
            password = passwordEncoder.encode(req.password),
            name = req.name,
            phone = req.phone,
            role = role,
            businessNumber = req.businessNumber
        )
        memberJpaRepository.save(member)
    }

    fun login(req: LoginRequest): TokenResponse {
        val member = memberJpaRepository.findByEmail(req.email)
            .orElseThrow { BusinessException(ErrorCode.INVALID_CREDENTIALS) }

        if (!passwordEncoder.matches(req.password, member.password)) {
            throw BusinessException(ErrorCode.INVALID_CREDENTIALS)
        }

        val accessToken = jwtTokenProvider.createAccessToken(member.id!!, member.email, member.role.name)
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id!!, member.email, member.role.name)

        return TokenResponse(accessToken, refreshToken, jwtTokenProvider.accessTokenValidityInSeconds)
    }

    fun refresh(req: RefreshRequest): TokenResponse {
        if (!jwtTokenProvider.validateToken(req.refreshToken)) {
            throw BusinessException(ErrorCode.INVALID_TOKEN)
        }

        val userId = jwtTokenProvider.getUserId(req.refreshToken)
        val member = memberJpaRepository.findById(userId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        val accessToken = jwtTokenProvider.createAccessToken(member.id!!, member.email, member.role.name)
        val refreshToken = jwtTokenProvider.createRefreshToken(member.id!!, member.email, member.role.name)

        return TokenResponse(accessToken, refreshToken, jwtTokenProvider.accessTokenValidityInSeconds)
    }

    fun logout(accessToken: String, refreshToken: String?) {
        val accessTokenExpiration = jwtTokenProvider.getTokenRemainingTimeMs(accessToken)
        if (accessTokenExpiration > 0) {
            tokenBlacklistService.blacklist(accessToken, accessTokenExpiration)
        }

        refreshToken?.let {
            val refreshTokenExpiration = jwtTokenProvider.getTokenRemainingTimeMs(it)
            if (refreshTokenExpiration > 0) {
                tokenBlacklistService.blacklist(it, refreshTokenExpiration)
            }
        }
    }
}
