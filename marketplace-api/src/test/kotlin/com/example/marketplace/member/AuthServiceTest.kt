package com.example.marketplace.member

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.SignupRequest
import com.example.marketplace.security.JwtTokenProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.security.crypto.password.PasswordEncoder
import java.util.*

class AuthServiceTest {

    private lateinit var authService: AuthService
    private lateinit var memberJpaRepository: MemberJpaRepository
    private lateinit var passwordEncoder: PasswordEncoder
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @BeforeEach
    fun setup() {
        memberJpaRepository = mockk()
        passwordEncoder = mockk()
        jwtTokenProvider = mockk()
        authService = AuthService(memberJpaRepository, passwordEncoder, jwtTokenProvider)
    }

    @Test
    fun `signup should create member when email is unique`() {
        val request = SignupRequest(
            email = "test@example.com",
            password = "password123",
            name = "Test User",
            role = "BUYER"
        )

        every { memberJpaRepository.existsByEmail(request.email) } returns false
        every { passwordEncoder.encode(request.password) } returns "encoded-password"
        every { memberJpaRepository.save(any()) } answers { firstArg() }

        authService.signup(request)

        verify { memberJpaRepository.save(any()) }
    }

    @Test
    fun `signup should throw exception when email already exists`() {
        val request = SignupRequest(
            email = "test@example.com",
            password = "password123",
            name = "Test User",
            role = "BUYER"
        )

        every { memberJpaRepository.existsByEmail(request.email) } returns true

        val exception = assertThrows<BusinessException> {
            authService.signup(request)
        }

        assertEquals(ErrorCode.EMAIL_ALREADY_EXISTS, exception.errorCode)
    }

    @Test
    fun `signup should throw exception when seller without business number`() {
        val request = SignupRequest(
            email = "test@example.com",
            password = "password123",
            name = "Test User",
            role = "SELLER",
            businessNumber = null
        )

        every { memberJpaRepository.existsByEmail(request.email) } returns false

        val exception = assertThrows<BusinessException> {
            authService.signup(request)
        }

        assertEquals(ErrorCode.BUSINESS_NUMBER_REQUIRED, exception.errorCode)
    }

    @Test
    fun `login should return tokens when credentials are valid`() {
        val request = LoginRequest(
            email = "test@example.com",
            password = "password123"
        )
        val member = Member(
            id = 1L,
            email = request.email,
            password = "encoded-password",
            role = Role.BUYER
        )

        every { memberJpaRepository.findByEmail(request.email) } returns Optional.of(member)
        every { passwordEncoder.matches(request.password, member.password) } returns true
        every { jwtTokenProvider.createAccessToken(any(), any(), any()) } returns "access-token"
        every { jwtTokenProvider.createRefreshToken(any(), any(), any()) } returns "refresh-token"
        every { jwtTokenProvider.accessTokenValidityInSeconds } returns 3600L

        val response = authService.login(request)

        assertEquals("access-token", response.accessToken)
        assertEquals("refresh-token", response.refreshToken)
    }

    @Test
    fun `login should throw exception when member not found`() {
        val request = LoginRequest(
            email = "test@example.com",
            password = "password123"
        )

        every { memberJpaRepository.findByEmail(request.email) } returns Optional.empty()

        val exception = assertThrows<BusinessException> {
            authService.login(request)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.errorCode)
    }

    @Test
    fun `login should throw exception when password is wrong`() {
        val request = LoginRequest(
            email = "test@example.com",
            password = "wrong-password"
        )
        val member = Member(
            id = 1L,
            email = request.email,
            password = "encoded-password",
            role = Role.BUYER
        )

        every { memberJpaRepository.findByEmail(request.email) } returns Optional.of(member)
        every { passwordEncoder.matches(request.password, member.password) } returns false

        val exception = assertThrows<BusinessException> {
            authService.login(request)
        }

        assertEquals(ErrorCode.INVALID_CREDENTIALS, exception.errorCode)
    }
}
