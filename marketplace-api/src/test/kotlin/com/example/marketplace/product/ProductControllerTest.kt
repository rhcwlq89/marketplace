package com.example.marketplace.product

import com.example.marketplace.member.AuthService
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.product.dto.CreateProductRequest
import com.example.marketplace.product.dto.UpdateProductRequest
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
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.ObjectMapper
import java.math.BigDecimal

@SpringBootTest
@Transactional
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class ProductControllerTest(
    context: WebApplicationContext,
    private val objectMapper: ObjectMapper,
    private val authService: AuthService,
    private val memberJpaRepository: MemberJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val productService: ProductService,
) : DescribeSpec({

    extension(SpringExtension)

    val restDocumentation = ManualRestDocumentation()

    val mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity())
        .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
        .build()

    beforeEach { testCase ->
        restDocumentation.beforeTest(
            ProductControllerTest::class.java,
            testCase.name.testName.replace(Regex("[^A-Za-z0-9-]"), "-")
        )
    }
    afterEach { restDocumentation.afterTest() }

    fun seedSellerProduct(sellerId: Long, name: String = "Test Product"): Long {
        val response = productService.createProduct(
            sellerId,
            CreateProductRequest(
                name = name,
                price = BigDecimal("10000"),
                stockQuantity = 50,
                status = "ON_SALE"
            )
        )
        return response.id
    }

    val sellerBearerHeader = requestHeaders(
        headerWithName(HttpHeaders.AUTHORIZATION).description("SELLER 권한 Bearer 토큰")
    )
    val productResponseFields = relaxedResponseFields(
        fieldWithPath("code").description("응답 코드"),
        fieldWithPath("message").description("응답 메시지"),
        fieldWithPath("data.id").description("상품 ID"),
        fieldWithPath("data.sellerId").description("판매자 ID"),
        fieldWithPath("data.sellerName").description("판매자 이름").optional(),
        fieldWithPath("data.categoryId").description("카테고리 ID").optional(),
        fieldWithPath("data.categoryName").description("카테고리 이름").optional(),
        fieldWithPath("data.name").description("상품 이름"),
        fieldWithPath("data.description").description("상품 설명").optional(),
        fieldWithPath("data.price").description("가격"),
        fieldWithPath("data.stockQuantity").description("재고 수량"),
        fieldWithPath("data.status").description("상태: DRAFT | ON_SALE | SOLD_OUT | DELETED"),
        fieldWithPath("data.salesCount").description("판매 횟수"),
        fieldWithPath("data.images").description("이미지 목록"),
        fieldWithPath("data.createdAt").description("생성 일시"),
        fieldWithPath("data.updatedAt").description("수정 일시"),
    )

    describe("POST /api/v1/products") {
        context("when caller is SELLER") {
            it("creates the product and returns 200") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val request = CreateProductRequest(
                    name = "Laptop",
                    description = "13-inch",
                    price = BigDecimal("1500000"),
                    stockQuantity = 10,
                    status = "ON_SALE"
                )

                mockMvc.post("/api/v1/products") {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.name") { value("Laptop") }
                    jsonPath("$.data.stockQuantity") { value(10) }
                }.andDo {
                    handle(
                        document(
                            "products-create",
                            sellerBearerHeader,
                            requestFields(
                                fieldWithPath("name").description("상품 이름"),
                                fieldWithPath("description").description("상품 설명").optional(),
                                fieldWithPath("price").description("가격 (0 이상)"),
                                fieldWithPath("stockQuantity").description("재고 수량 (0 이상, 기본 0)").optional(),
                                fieldWithPath("categoryId").description("카테고리 ID").optional(),
                                fieldWithPath("status").description("상태 (기본 DRAFT)").optional(),
                            ),
                            productResponseFields
                        )
                    )
                }

                productJpaRepository.findAll().any { it.name == "Laptop" } shouldBe true
            }
        }

        context("when caller is BUYER") {
            it("returns 403") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )

                mockMvc.post("/api/v1/products") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(
                        CreateProductRequest(name = "x", price = BigDecimal("1000"))
                    )
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }

        context("when request name is blank") {
            it("returns 400") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )

                mockMvc.post("/api/v1/products") {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(
                        CreateProductRequest(name = "", price = BigDecimal("1000"))
                    )
                }.andExpect {
                    status { isBadRequest() }
                }
            }
        }
    }

    describe("GET /api/v1/products") {
        context("public access") {
            it("returns a paged result without authentication") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                seedSellerProduct(seller.memberId, "Product A")

                mockMvc.get("/api/v1/products").andExpect {
                    status { isOk() }
                    jsonPath("$.data.content") { isArray() }
                }.andDo {
                    handle(
                        document(
                            "products-search",
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data.content").description("상품 목록"),
                                fieldWithPath("data.content[].id").description("상품 ID"),
                                fieldWithPath("data.content[].name").description("상품 이름"),
                                fieldWithPath("data.content[].price").description("가격"),
                                fieldWithPath("data.content[].status").description("상태"),
                                fieldWithPath("data.totalElements").description("전체 결과 수"),
                                fieldWithPath("data.totalPages").description("전체 페이지 수"),
                                fieldWithPath("data.size").description("페이지 크기"),
                                fieldWithPath("data.number").description("현재 페이지 (0-base)"),
                            )
                        )
                    )
                }
            }
        }
    }

    describe("GET /api/v1/products/{productId}") {
        context("when the product exists") {
            it("returns the product details") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val productId = seedSellerProduct(seller.memberId, "Visible Product")

                mockMvc.get("/api/v1/products/{productId}", productId).andExpect {
                    status { isOk() }
                    jsonPath("$.data.name") { value("Visible Product") }
                }.andDo {
                    handle(
                        document(
                            "products-get",
                            pathParameters(parameterWithName("productId").description("상품 ID")),
                            productResponseFields
                        )
                    )
                }
            }
        }

        context("when the product does not exist") {
            it("returns 404") {
                mockMvc.get("/api/v1/products/99999").andExpect {
                    status { isNotFound() }
                }
            }
        }
    }

    describe("PATCH /api/v1/products/{productId}") {
        context("when caller owns the product") {
            it("updates the product") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "owner@example.com", role = "SELLER"
                )
                val productId = seedSellerProduct(seller.memberId)
                val request = UpdateProductRequest(name = "Renamed", price = BigDecimal("20000"))

                mockMvc.patch("/api/v1/products/{productId}", productId) {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.name") { value("Renamed") }
                }.andDo {
                    handle(
                        document(
                            "products-update",
                            sellerBearerHeader,
                            pathParameters(parameterWithName("productId").description("상품 ID")),
                            requestFields(
                                fieldWithPath("name").description("새 상품 이름").optional(),
                                fieldWithPath("description").description("새 상품 설명").optional(),
                                fieldWithPath("price").description("새 가격").optional(),
                                fieldWithPath("stockQuantity").description("새 재고 수량").optional(),
                                fieldWithPath("categoryId").description("새 카테고리 ID").optional(),
                                fieldWithPath("status").description("새 상태").optional(),
                            ),
                            productResponseFields
                        )
                    )
                }
            }
        }

        context("when caller is a different SELLER") {
            it("returns 403") {
                val owner = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "owner@example.com", role = "SELLER"
                )
                val intruder = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "intruder@example.com", role = "SELLER"
                )
                val productId = seedSellerProduct(owner.memberId)

                mockMvc.patch("/api/v1/products/$productId") {
                    header(HttpHeaders.AUTHORIZATION, intruder.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(UpdateProductRequest(name = "hack"))
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }
    }

    describe("DELETE /api/v1/products/{productId}") {
        context("when caller owns the product") {
            it("soft-deletes by setting status to DELETED") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val productId = seedSellerProduct(seller.memberId)

                mockMvc.delete("/api/v1/products/{productId}", productId) {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                }.andExpect {
                    status { isOk() }
                }.andDo {
                    handle(
                        document(
                            "products-delete",
                            sellerBearerHeader,
                            pathParameters(parameterWithName("productId").description("상품 ID")),
                            responseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data").description("응답 데이터 (비어있음)").optional(),
                            )
                        )
                    )
                }

                productJpaRepository.findById(productId).get().status shouldBe ProductStatus.DELETED
            }
        }
    }

    describe("POST /api/v1/products/{productId}/images") {
        context("when caller does not own the product") {
            it("returns 403 before any file is written") {
                val owner = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "owner@example.com", role = "SELLER"
                )
                val intruder = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "intruder@example.com", role = "SELLER"
                )
                val productId = seedSellerProduct(owner.memberId)

                mockMvc.multipart("/api/v1/products/$productId/images") {
                    file(
                        org.springframework.mock.web.MockMultipartFile(
                            "files", "a.jpg", MediaType.IMAGE_JPEG_VALUE, byteArrayOf(0x01)
                        )
                    )
                    header(HttpHeaders.AUTHORIZATION, intruder.bearerToken)
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }
    }

    describe("GET /api/v1/products/popular") {
        context("public access") {
            it("returns a list without authentication") {
                mockMvc.get("/api/v1/products/popular").andExpect {
                    status { isOk() }
                    jsonPath("$.data") { isArray() }
                }.andDo {
                    handle(
                        document(
                            "products-popular",
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data").description("인기 상품 목록 (최대 10개, 판매량 내림차순)"),
                            )
                        )
                    )
                }
            }
        }
    }
})
