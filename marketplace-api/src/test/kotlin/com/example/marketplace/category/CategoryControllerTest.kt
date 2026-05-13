package com.example.marketplace.category

import com.example.marketplace.category.dto.CreateCategoryRequest
import com.example.marketplace.member.AuthService
import com.example.marketplace.member.MemberJpaRepository
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
class CategoryControllerTest(
    context: WebApplicationContext,
    private val objectMapper: ObjectMapper,
    private val authService: AuthService,
    private val memberJpaRepository: MemberJpaRepository,
    private val categoryJpaRepository: CategoryJpaRepository,
) : DescribeSpec({

    extension(SpringExtension)

    val restDocumentation = ManualRestDocumentation()

    val mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity())
        .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
        .build()

    beforeEach { testCase ->
        restDocumentation.beforeTest(
            CategoryControllerTest::class.java,
            testCase.name.testName.replace(Regex("[^A-Za-z0-9-]"), "-")
        )
    }
    afterEach { restDocumentation.afterTest() }

    describe("GET /api/v1/categories") {
        context("public access") {
            it("returns 200 without authentication") {
                categoryJpaRepository.save(Category(name = "Electronics", displayOrder = 1))

                mockMvc.get("/api/v1/categories").andExpect {
                    status { isOk() }
                    jsonPath("$.code") { value("SUC200") }
                }.andDo {
                    handle(
                        document(
                            "categories-list",
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data").description("카테고리 목록"),
                                fieldWithPath("data[].id").description("카테고리 ID"),
                                fieldWithPath("data[].name").description("이름"),
                                fieldWithPath("data[].parentId").description("부모 카테고리 ID").optional(),
                                fieldWithPath("data[].displayOrder").description("표시 순서"),
                            )
                        )
                    )
                }
            }
        }
    }

    describe("POST /api/v1/admin/categories") {
        context("when caller is ADMIN") {
            it("creates the category and returns 200") {
                val admin = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "admin@example.com", role = "ADMIN"
                )
                val request = CreateCategoryRequest(name = "Electronics", displayOrder = 1)

                mockMvc.post("/api/v1/admin/categories") {
                    header(HttpHeaders.AUTHORIZATION, admin.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.name") { value("Electronics") }
                }.andDo {
                    handle(
                        document(
                            "admin-categories-create",
                            requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("ADMIN 권한 Bearer 토큰"),
                            ),
                            requestFields(
                                fieldWithPath("name").description("카테고리 이름"),
                                fieldWithPath("parentId").description("부모 카테고리 ID").optional(),
                                fieldWithPath("displayOrder").description("표시 순서 (기본 0)").optional(),
                            ),
                            responseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data.id").description("생성된 카테고리 ID"),
                                fieldWithPath("data.name").description("이름"),
                                fieldWithPath("data.parentId").description("부모 카테고리 ID").optional(),
                                fieldWithPath("data.displayOrder").description("표시 순서"),
                            )
                        )
                    )
                }

                categoryJpaRepository.findAll().any { it.name == "Electronics" } shouldBe true
            }
        }

        context("when caller is not ADMIN") {
            it("returns 403") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val request = CreateCategoryRequest(name = "Books")

                mockMvc.post("/api/v1/admin/categories") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }

        context("when no token is provided") {
            it("returns 401") {
                val request = CreateCategoryRequest(name = "Books")

                mockMvc.post("/api/v1/admin/categories") {
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isUnauthorized() }
                }
            }
        }
    }
})
