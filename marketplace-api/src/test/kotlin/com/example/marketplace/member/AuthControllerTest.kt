package com.example.marketplace.member

import com.example.marketplace.member.dto.LoginRequest
import com.example.marketplace.member.dto.SignupRequest
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.post
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Transactional
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuthControllerTest(
    context: WebApplicationContext,
    private val objectMapper: ObjectMapper,
    private val authService: AuthService,
    private val memberJpaRepository: MemberJpaRepository,
) : DescribeSpec({

    extension(SpringExtension)

    val restDocumentation = ManualRestDocumentation()

    val mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity())
        .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
        .build()

    beforeEach { testCase ->
        restDocumentation.beforeTest(
            AuthControllerTest::class.java,
            testCase.name.testName.replace(Regex("[^A-Za-z0-9-]"), "-")
        )
    }
    afterEach { restDocumentation.afterTest() }

    describe("POST /api/v1/auth/signup") {
        context("when request is valid") {
            it("returns 200 and persists the member") {
                val request = SignupRequest(
                    email = "test@example.com",
                    password = "password123",
                    name = "Test User",
                    role = "BUYER"
                )

                mockMvc.post("/api/v1/auth/signup") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                }.andDo {
                    handle(
                        document(
                            "auth-signup",
                            requestFields(
                                fieldWithPath("email").description("이메일 (Email 형식)"),
                                fieldWithPath("password").description("비밀번호"),
                                fieldWithPath("name").description("이름").optional(),
                                fieldWithPath("phone").description("전화번호").optional(),
                                fieldWithPath("role").description("역할: BUYER | SELLER | ADMIN").optional(),
                                fieldWithPath("businessNumber").description("사업자 등록번호 (SELLER일 때 필수)").optional(),
                            ),
                            responseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data").description("응답 데이터 (signup은 비어있음)").optional(),
                            )
                        )
                    )
                }

                memberJpaRepository.existsByEmail(request.email) shouldBe true
            }
        }

        context("when email format is invalid") {
            it("returns 400 Bad Request") {
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
    }

    describe("POST /api/v1/auth/login") {
        context("after a member has signed up") {
            it("returns access and refresh tokens") {
                authService.signup(
                    SignupRequest(
                        email = "login@example.com",
                        password = "password123",
                        name = "Login User",
                        role = "BUYER"
                    )
                )

                val request = LoginRequest(
                    email = "login@example.com",
                    password = "password123"
                )

                mockMvc.post("/api/v1/auth/login") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.code") { value("SUC200") }
                    jsonPath("$.data.accessToken") { isNotEmpty() }
                    jsonPath("$.data.refreshToken") { isNotEmpty() }
                }.andDo {
                    handle(
                        document(
                            "auth-login",
                            requestFields(
                                fieldWithPath("email").description("이메일"),
                                fieldWithPath("password").description("비밀번호"),
                            ),
                            responseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data.accessToken").description("Access token (Bearer)"),
                                fieldWithPath("data.refreshToken").description("Refresh token"),
                                fieldWithPath("data.expiresIn").description("Access token 만료 시간(초)"),
                            )
                        )
                    )
                }
            }
        }
    }
})
