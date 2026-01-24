package com.example.marketplace.order

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.Member
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.member.Role
import com.example.marketplace.order.dto.CreateOrderRequest
import com.example.marketplace.order.dto.OrderItemRequest
import com.example.marketplace.order.dto.ShippingAddressRequest
import com.example.marketplace.product.Product
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.*

class OrderServiceTest {

    private lateinit var orderService: OrderService
    private lateinit var orderJpaRepository: OrderJpaRepository
    private lateinit var productJpaRepository: ProductJpaRepository
    private lateinit var memberJpaRepository: MemberJpaRepository
    private lateinit var eventPublisher: ApplicationEventPublisher

    private val buyer = Member(
        id = 1L,
        email = "buyer@example.com",
        password = "password",
        role = Role.BUYER
    )

    private val seller = Member(
        id = 2L,
        email = "seller@example.com",
        password = "password",
        role = Role.SELLER,
        businessNumber = "123-45-67890"
    )

    @BeforeEach
    fun setup() {
        orderJpaRepository = mockk()
        productJpaRepository = mockk()
        memberJpaRepository = mockk()
        eventPublisher = mockk(relaxed = true)
        orderService = OrderService(orderJpaRepository, productJpaRepository, memberJpaRepository, eventPublisher)
    }

    @Test
    fun `createOrder should create order successfully`() {
        val product = Product(
            id = 1L,
            seller = seller,
            name = "Test Product",
            price = BigDecimal("10000"),
            stockQuantity = 100,
            status = ProductStatus.ON_SALE
        )
        val request = CreateOrderRequest(
            orderItems = listOf(OrderItemRequest(productId = 1L, quantity = 2)),
            shippingAddress = ShippingAddressRequest(
                zipCode = "12345",
                address = "Test Address",
                addressDetail = "Detail",
                receiverName = "Receiver",
                receiverPhone = "010-1234-5678"
            )
        )

        every { memberJpaRepository.findById(buyer.id!!) } returns Optional.of(buyer)
        every { productJpaRepository.findById(product.id!!) } returns Optional.of(product)
        every { productJpaRepository.decreaseStockAtomically(product.id!!, 2) } returns 1
        every { orderJpaRepository.save(any()) } answers {
            val order = firstArg<Order>()
            order.id = 1L
            order.orderItems.forEachIndexed { index, item ->
                item.id = (index + 1).toLong()
            }
            order
        }

        val response = orderService.createOrder(buyer.id!!, request)

        assertEquals(buyer.id, response.buyerId)
        assertEquals(BigDecimal("20000"), response.totalAmount)
        assertEquals(1, response.orderItems.size)
    }

    @Test
    fun `createOrder should throw exception when empty order items`() {
        val request = CreateOrderRequest(
            orderItems = emptyList(),
            shippingAddress = ShippingAddressRequest(
                zipCode = "12345",
                address = "Test Address",
                addressDetail = "Detail",
                receiverName = "Receiver",
                receiverPhone = "010-1234-5678"
            )
        )

        val exception = assertThrows<BusinessException> {
            orderService.createOrder(buyer.id!!, request)
        }

        assertEquals(ErrorCode.EMPTY_ORDER_ITEMS, exception.errorCode)
    }

    @Test
    fun `cancelOrder should cancel order and restore stock`() {
        val product = Product(
            id = 1L,
            seller = seller,
            name = "Test Product",
            price = BigDecimal("10000"),
            stockQuantity = 98,
            status = ProductStatus.ON_SALE
        )
        val orderItem = OrderItem(
            id = 1L,
            product = product,
            seller = seller,
            productName = "Test Product",
            productPrice = BigDecimal("10000"),
            quantity = 2,
            subtotal = BigDecimal("20000")
        )
        val order = Order(
            id = 1L,
            buyer = buyer,
            status = OrderStatus.PENDING,
            totalAmount = BigDecimal("20000")
        ).apply {
            orderItems.add(orderItem)
            orderItem.order = this
        }

        every { orderJpaRepository.findById(order.id!!) } returns Optional.of(order)
        every { productJpaRepository.restoreStockAtomically(product.id!!, 2) } returns 1
        every { orderJpaRepository.save(any()) } answers { firstArg() }

        val response = orderService.cancelOrder(buyer.id!!, order.id!!)

        assertEquals("CANCELLED", response.status)
        verify { productJpaRepository.restoreStockAtomically(product.id!!, 2) }
    }

    @Test
    fun `cancelOrder should throw exception when not owned by buyer`() {
        val otherBuyer = Member(id = 3L, email = "other@example.com", password = "password", role = Role.BUYER)
        val order = Order(
            id = 1L,
            buyer = otherBuyer,
            status = OrderStatus.PENDING
        )

        every { orderJpaRepository.findById(order.id!!) } returns Optional.of(order)

        val exception = assertThrows<BusinessException> {
            orderService.cancelOrder(buyer.id!!, order.id!!)
        }

        assertEquals(ErrorCode.ORDER_NOT_OWNED, exception.errorCode)
    }

    @Test
    fun `cancelOrder should throw exception when order status is not cancelable`() {
        val order = Order(
            id = 1L,
            buyer = buyer,
            status = OrderStatus.SHIPPED
        )

        every { orderJpaRepository.findById(order.id!!) } returns Optional.of(order)

        val exception = assertThrows<BusinessException> {
            orderService.cancelOrder(buyer.id!!, order.id!!)
        }

        assertEquals(ErrorCode.CANNOT_CANCEL_ORDER, exception.errorCode)
    }
}
