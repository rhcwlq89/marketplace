package com.example.marketplace.order

import com.example.marketplace.member.AuthService
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.order.dto.CreateOrderRequest
import com.example.marketplace.order.dto.OrderItemRequest
import com.example.marketplace.order.dto.ShippingAddressRequest
import com.example.marketplace.order.dto.UpdateOrderStatusRequest
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductService
import com.example.marketplace.product.dto.CreateProductRequest
import com.example.marketplace.support.AuthenticatedMember
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
import org.springframework.restdocs.request.RequestDocumentation.parameterWithName
import org.springframework.restdocs.request.RequestDocumentation.pathParameters
import org.springframework.test.context.TestConstructor
import org.springframework.test.web.servlet.get
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
class OrderControllerTest(
    context: WebApplicationContext,
    private val objectMapper: ObjectMapper,
    private val authService: AuthService,
    private val memberJpaRepository: MemberJpaRepository,
    private val productService: ProductService,
    private val productJpaRepository: ProductJpaRepository,
    private val orderService: OrderService,
    private val orderJpaRepository: OrderJpaRepository,
) : DescribeSpec({

    extension(SpringExtension)

    val restDocumentation = ManualRestDocumentation()

    val mockMvc = MockMvcBuilders.webAppContextSetup(context)
        .apply<DefaultMockMvcBuilder>(springSecurity())
        .apply<DefaultMockMvcBuilder>(documentationConfiguration(restDocumentation))
        .build()

    beforeEach { testCase ->
        restDocumentation.beforeTest(
            OrderControllerTest::class.java,
            testCase.name.testName.replace(Regex("[^A-Za-z0-9-]"), "-")
        )
    }
    afterEach { restDocumentation.afterTest() }

    val defaultShipping = ShippingAddressRequest(
        zipCode = "12345",
        address = "Test Address",
        addressDetail = "Detail",
        receiverName = "Receiver",
        receiverPhone = "010-1234-5678"
    )

    fun seedProduct(sellerId: Long, stock: Int = 100): Long {
        return productService.createProduct(
            sellerId,
            CreateProductRequest(
                name = "Test Product",
                price = BigDecimal("10000"),
                stockQuantity = stock,
                status = "ON_SALE"
            )
        ).id
    }

    fun placeOrder(buyer: AuthenticatedMember, productId: Long, quantity: Int = 1): Long {
        return orderService.createOrder(
            buyer.memberId,
            CreateOrderRequest(
                orderItems = listOf(OrderItemRequest(productId, quantity)),
                shippingAddress = defaultShipping,
            )
        ).id
    }

    val bearerHeader = requestHeaders(
        headerWithName(HttpHeaders.AUTHORIZATION).description("Bearer 액세스 토큰")
    )
    val orderResponseFields = relaxedResponseFields(
        fieldWithPath("code").description("응답 코드"),
        fieldWithPath("message").description("응답 메시지"),
        fieldWithPath("data.id").description("주문 ID"),
        fieldWithPath("data.orderNumber").description("주문 번호"),
        fieldWithPath("data.buyerId").description("구매자 ID"),
        fieldWithPath("data.buyerName").description("구매자 이름").optional(),
        fieldWithPath("data.status").description("주문 상태: PENDING | PAID | SHIPPED | DELIVERED | CANCELLED"),
        fieldWithPath("data.totalAmount").description("총 결제 금액"),
        fieldWithPath("data.shippingAddress.zipCode").description("우편번호"),
        fieldWithPath("data.shippingAddress.address").description("주소"),
        fieldWithPath("data.shippingAddress.addressDetail").description("상세 주소").optional(),
        fieldWithPath("data.shippingAddress.receiverName").description("수령인 이름"),
        fieldWithPath("data.shippingAddress.receiverPhone").description("수령인 전화번호"),
        fieldWithPath("data.orderItems[].id").description("주문 항목 ID"),
        fieldWithPath("data.orderItems[].productId").description("상품 ID"),
        fieldWithPath("data.orderItems[].sellerId").description("판매자 ID"),
        fieldWithPath("data.orderItems[].productName").description("상품 이름"),
        fieldWithPath("data.orderItems[].productPrice").description("상품 단가"),
        fieldWithPath("data.orderItems[].quantity").description("수량"),
        fieldWithPath("data.orderItems[].subtotal").description("소계 (단가×수량)"),
        fieldWithPath("data.createdAt").description("생성 일시"),
        fieldWithPath("data.updatedAt").description("수정 일시"),
    )

    describe("POST /api/v1/orders") {
        context("when BUYER creates a valid order") {
            it("returns 200 and persists the order") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                val request = CreateOrderRequest(
                    orderItems = listOf(OrderItemRequest(productId, 2)),
                    shippingAddress = defaultShipping,
                )

                mockMvc.post("/api/v1/orders") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(request)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.totalAmount") { value(20000) }
                }.andDo {
                    handle(
                        document(
                            "orders-create",
                            requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("BUYER 권한 Bearer 토큰"),
                            ),
                            requestFields(
                                fieldWithPath("orderItems").description("주문 항목 (1개 이상)"),
                                fieldWithPath("orderItems[].productId").description("상품 ID"),
                                fieldWithPath("orderItems[].quantity").description("수량 (1 이상)"),
                                fieldWithPath("shippingAddress.zipCode").description("우편번호"),
                                fieldWithPath("shippingAddress.address").description("주소"),
                                fieldWithPath("shippingAddress.addressDetail").description("상세 주소").optional(),
                                fieldWithPath("shippingAddress.receiverName").description("수령인 이름"),
                                fieldWithPath("shippingAddress.receiverPhone").description("수령인 전화번호"),
                            ),
                            orderResponseFields
                        )
                    )
                }

                productJpaRepository.findById(productId).get().stockQuantity shouldBe 98
            }
        }

        context("when caller is SELLER") {
            it("returns 403") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )

                mockMvc.post("/api/v1/orders") {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(
                        CreateOrderRequest(
                            orderItems = listOf(OrderItemRequest(1L, 1)),
                            shippingAddress = defaultShipping,
                        )
                    )
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }

        context("when orderItems is empty") {
            it("returns 400 from bean validation") {
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )

                mockMvc.post("/api/v1/orders") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(
                        CreateOrderRequest(orderItems = emptyList(), shippingAddress = defaultShipping)
                    )
                }.andExpect {
                    status { isBadRequest() }
                }
            }
        }
    }

    describe("GET /api/v1/orders") {
        context("when authenticated") {
            it("returns the buyer's own orders") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                placeOrder(buyer, productId)

                mockMvc.get("/api/v1/orders") {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.content.length()") { value(1) }
                }.andDo {
                    handle(
                        document(
                            "orders-my-list",
                            bearerHeader,
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data.content").description("주문 목록"),
                                fieldWithPath("data.content[].id").description("주문 ID"),
                                fieldWithPath("data.content[].orderNumber").description("주문 번호"),
                                fieldWithPath("data.content[].status").description("주문 상태"),
                                fieldWithPath("data.content[].totalAmount").description("총 금액"),
                                fieldWithPath("data.totalElements").description("전체 주문 수"),
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

    describe("GET /api/v1/orders/{orderId}") {
        context("when the buyer views their own order") {
            it("returns the order details") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                val orderId = placeOrder(buyer, productId)

                mockMvc.get("/api/v1/orders/{orderId}", orderId) {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.id") { value(orderId.toInt()) }
                }.andDo {
                    handle(
                        document(
                            "orders-get",
                            bearerHeader,
                            pathParameters(parameterWithName("orderId").description("주문 ID")),
                            orderResponseFields
                        )
                    )
                }
            }
        }
    }

    describe("POST /api/v1/orders/{orderId}/cancel") {
        context("when the buyer cancels a PENDING order") {
            it("returns 200, restores stock, and marks order CANCELLED") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId, stock = 50)
                val orderId = placeOrder(buyer, productId, quantity = 3)

                mockMvc.post("/api/v1/orders/{orderId}/cancel", orderId) {
                    header(HttpHeaders.AUTHORIZATION, buyer.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.status") { value("CANCELLED") }
                }.andDo {
                    handle(
                        document(
                            "orders-cancel",
                            requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("BUYER 권한 Bearer 토큰"),
                            ),
                            pathParameters(parameterWithName("orderId").description("주문 ID")),
                            orderResponseFields
                        )
                    )
                }

                productJpaRepository.findById(productId).get().stockQuantity shouldBe 50
            }
        }

        context("when a different buyer tries to cancel") {
            it("returns 403") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val owner = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "owner@example.com", role = "BUYER"
                )
                val intruder = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "intruder@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                val orderId = placeOrder(owner, productId)

                mockMvc.post("/api/v1/orders/$orderId/cancel") {
                    header(HttpHeaders.AUTHORIZATION, intruder.bearerToken)
                }.andExpect {
                    status { isForbidden() }
                }
            }
        }
    }

    describe("GET /api/v1/sellers/orders") {
        context("when caller is SELLER") {
            it("returns orders containing the seller's products") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                placeOrder(buyer, productId)

                mockMvc.get("/api/v1/sellers/orders") {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.content.length()") { value(1) }
                }.andDo {
                    handle(
                        document(
                            "orders-seller-list",
                            requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("SELLER 권한 Bearer 토큰"),
                            ),
                            relaxedResponseFields(
                                fieldWithPath("code").description("응답 코드"),
                                fieldWithPath("message").description("응답 메시지"),
                                fieldWithPath("data.content").description("주문 목록 (해당 판매자의 상품을 포함하는 주문)"),
                                fieldWithPath("data.content[].id").description("주문 ID"),
                                fieldWithPath("data.content[].orderNumber").description("주문 번호"),
                                fieldWithPath("data.content[].status").description("주문 상태"),
                                fieldWithPath("data.totalElements").description("전체 주문 수"),
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

    describe("PATCH /api/v1/sellers/orders/{orderId}/status") {
        context("when the seller of the order updates status") {
            it("returns the updated status") {
                val seller = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "seller@example.com", role = "SELLER"
                )
                val buyer = TestAuthSupport.signupAndLogin(
                    authService, memberJpaRepository,
                    email = "buyer@example.com", role = "BUYER"
                )
                val productId = seedProduct(seller.memberId)
                val orderId = placeOrder(buyer, productId)

                mockMvc.patch("/api/v1/sellers/orders/{orderId}/status", orderId) {
                    header(HttpHeaders.AUTHORIZATION, seller.bearerToken)
                    contentType = MediaType.APPLICATION_JSON
                    content = objectMapper.writeValueAsString(UpdateOrderStatusRequest(status = "SHIPPED"))
                }.andExpect {
                    status { isOk() }
                    jsonPath("$.data.status") { value("SHIPPED") }
                }.andDo {
                    handle(
                        document(
                            "orders-seller-status-update",
                            requestHeaders(
                                headerWithName(HttpHeaders.AUTHORIZATION).description("SELLER 권한 Bearer 토큰"),
                            ),
                            pathParameters(parameterWithName("orderId").description("주문 ID")),
                            requestFields(
                                fieldWithPath("status").description("새 주문 상태"),
                            ),
                            orderResponseFields
                        )
                    )
                }

                orderJpaRepository.findById(orderId).get().status shouldBe OrderStatus.SHIPPED
            }
        }
    }
})
