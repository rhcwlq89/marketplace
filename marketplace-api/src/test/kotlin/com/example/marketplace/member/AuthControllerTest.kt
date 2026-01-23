package com.example.marketplace.member

import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.SignupRequest
import com.example.marketplace.member.dto.TokenResponse
import com.fasterxml.jackson.databind.ObjectMapper
import com.ninjasquad.springmockk.MockkBean
import io.mockk.every
import io.mockk.justRun
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AuthControllerTest {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    @MockkBean
    private lateinit var authService: AuthService

    @Test
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
        }.andExpect {
            status { isOk() }
        }
    }

    @Test
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
        }.andExpect {
            status { isOk() }
            jsonPath("$.code") { value("SUC200") }
            jsonPath("$.data.accessToken") { value("access-token") }
            jsonPath("$.data.refreshToken") { value("refresh-token") }
        }
    }

    @Test
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
        }.andExpect {
            status { isBadRequest() }
        }
    }
}
