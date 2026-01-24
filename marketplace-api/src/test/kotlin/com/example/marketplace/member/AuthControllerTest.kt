package com.example.marketplace.member

import com.example.marketplace.config.RateLimitingFilter
import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.SignupRequest
import com.example.marketplace.member.dto.TokenResponse
import com.example.marketplace.security.JwtTokenProvider
import com.example.marketplace.security.TokenBlacklistService
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import io.mockk.every
import io.mockk.justRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.FilterType
import org.springframework.http.MediaType
import org.springframework.security.test.context.support.WithAnonymousUser
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [AuthController::class],
    excludeFilters = [ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = [RateLimitingFilter::class])]
)
@ActiveProfiles("local")
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var authService: AuthService

    @MockkBean
    private lateinit var jwtTokenProvider: JwtTokenProvider

    @MockkBean
    private lateinit var tokenBlacklistService: TokenBlacklistService

    @MockkBean
    private lateinit var rateLimiterRegistry: RateLimiterRegistry

    @Test
    @WithAnonymousUser
    fun `signup should return 200 when valid request`() {
        val request = SignupRequest(
            email = "test@example.com",
            password = "password123",
            name = "Test User",
            role = "BUYER"
        )

        justRun { authService.signup(any()) }

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(csrf())
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
    @WithAnonymousUser
    fun `login should return token when valid credentials`() {
        val request = LoginRequest(
            email = "test@example.com",
            password = "password123"
        )
        val tokenResponse = TokenResponse(
            accessToken = "access-token",
            refreshToken = "refresh-token",
            expiresIn = 3600
        )

        every { authService.login(any()) } returns tokenResponse

        mockMvc.post("/api/v1/auth/login") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(csrf())
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("SUC200") }
            jsonPath("$.data.accessToken") { value("access-token") }
            jsonPath("$.data.refreshToken") { value("refresh-token") }
        }
    }

    @Test
    @WithAnonymousUser
    fun `signup should return 400 when email is invalid`() {
        val request = SignupRequest(
            email = "invalid-email",
            password = "password123",
            name = "Test User",
            role = "BUYER"
        )

        mockMvc.post("/api/v1/auth/signup") {
            contentType = MediaType.APPLICATION_JSON
            content = objectMapper.writeValueAsString(request)
            with(csrf())
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
