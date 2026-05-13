package com.example.marketplace.member

import com.example.marketplace.member.dto.UpdateMemberRequest
import com.example.marketplace.support.TestAuthSupport
import io.kotest.core.spec.style.DescribeSpec
import io.kotest.extensions.spring.SpringExtension
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.restdocs.ManualRestDocumentation
import org.springframework.restdocs.headers.HeaderDocumentation.headerWithName
import org.springframework.restdocs.headers.HeaderDocumentation.requestHeaders
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.document
import org.springframework.restdocs.mockmvc.MockMvcRestDocumentation.documentationConfiguration
import org.springframework.restdocs.payload.PayloadDocumentation.fieldWithPath
import org.springframework.restdocs.payload.PayloadDocumentation.relaxedResponseFields
import org.springframework.restdocs.payload.PayloadDocumentation.requestFields
import org.springframework.restdocs.payload.PayloadDocumentation.responseFields
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper

@SpringBootTest
@Transactional
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class MembersControllerTest(
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
            MembersControllerTest::class.java,
            testCase.name.testName.replace(Regex("[^A-Za-z0-9-]"), "-")
        )
    }
    afterEach { restDocumentation.afterTest() }

    val authBearerHeader = requestHeaders(
        headerWithName(HttpHeaders.AUTHORIZATION).description("Bearer 액세스 토큰")
    )
    val memberResponseFields = responseFields(
        fieldWithPath("code").description("응답 코드"),
        fieldWithPath("message").description("응답 메시지"),
        fieldWithPath("data.id").description("회원 ID"),
        fieldWithPath("data.email").description("이메일"),
        fieldWithPath("data.name").description("이름").optional(),
        fieldWithPath("data.phone").description("전화번호").optional(),
        fieldWithPath("data.role").description("역할: BUYER | SELLER | ADMIN"),
        fieldWithPath("data.businessNumber").description("사업자 등록번호 (SELLER만)").optional(),
        fieldWithPath("data.createdAt").description("가입 일시"),
    )

    describe("GET /api/v1/members/me") {
        context("when authenticated") {
            it("returns the caller's own profile") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "me@example.com", role = "BUYER"
                )

                mockMvc.get("/api/v1/members/me") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.email") { value("me@example.com") }
                    jsonPath("$.data.role") { value("BUYER") }
                }.andDo {
                    handle(document("members-me-get", authBearerHeader, memberResponseFields))
                }
            }
        }

        context("when no token is provided") {
            it("returns 401") {
                mockMvc.get("/api/v1/members/me").andExpect {
                    status { isUnauthorized() }
                }
            }
        }
    }

    describe("PATCH /api/v1/members/me") {
        context("when authenticated") {
            it("updates name and phone, returning the new values") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "patch@example.com", role = "BUYER"
                )
                val request = UpdateMemberRequest(name = "Updated Name", phone = "010-9999-8888")

                mockMvc.patch("/api/v1/members/me") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.name") { value("Updated Name") }
                    jsonPath("$.data.phone") { value("010-9999-8888") }
                }.andDo {
                    handle(
                        document(
                            "members-me-update",
                            authBearerHeader,
                            requestFields(
                                fieldWithPath("name").description("새 이름").optional(),
                                fieldWithPath("phone").description("새 전화번호").optional(),
                            ),
                            memberResponseFields
                        )
                    )
                }

                val persisted = memberJpaRepository.findById(buyer.memberId).get()
                persisted.name shouldBe "Updated Name"
                persisted.phone shouldBe "010-9999-8888"
            }
        }
    }

    describe("GET /api/v1/admin/members") {
        context("when caller is ADMIN") {
            it("returns all members") {
                TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer1@example.com", role = "BUYER"
                )
                val admin = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "admin@example.com", role = "ADMIN"
                )

                mockMvc.get("/api/v1/admin/members") {
                    header(HttpHeaders.AUTHORIZATION, admin.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.length()") { value(2) }
                }.andDo {
                    handle(
                        document(
                            "admin-members-list",
                            authBearerHeader,
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data").description("회원 목록"),
                                fieldWithPath("data[].id").description("회원 ID"),
                                fieldWithPath("data[].email").description("이메일"),
                                fieldWithPath("data[].role").description("역할"),
                            )
                        )
                    )
                }
            }
        }

        context("when caller is not ADMIN") {
            it("returns 403") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )

                mockMvc.get("/api/v1/admin/members") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }
    }
})
