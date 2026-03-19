package com.example.marketplace.checkout

import com.example.marketplace.cart.CartJpaRepository
import com.example.marketplace.checkout.dto.CheckoutRequest
import com.example.marketplace.checkout.dto.CheckoutResponse
import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.DistributedLock
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.order.Order
import com.example.marketplace.order.OrderItem
import com.example.marketplace.order.OrderJpaRepository
import com.example.marketplace.order.ShippingAddress
import com.example.marketplace.order.dto.OrderResponse
import com.example.marketplace.order.event.OrderCreatedEvent
import com.example.marketplace.outbox.OutboxEventService
import com.example.marketplace.product.ProductJpaRepository
import com.example.marketplace.product.ProductStatus
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class CheckoutService(
    private val cartJpaRepository: CartJpaRepository,
    private val orderJpaRepository: OrderJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val outboxEventService: OutboxEventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @DistributedLock(key = "'checkout:' + #buyerId", waitTime = 5, leaseTime = 30)
    @CircuitBreaker(name = "checkoutService", fallbackMethod = "checkoutFallback")
    @Bulkhead(name = "checkoutService")
    @Retry(name = "checkoutService")
    fun checkout(buyerId: Long, req: CheckoutRequest): CheckoutResponse {
        // 1. 장바구니 조회
        val cart = cartJpaRepository.findByMemberIdWithItems(buyerId)
            .orElseThrow { BusinessException(ErrorCode.CART_NOT_FOUND) }

        if (cart.cartItems.isEmpty()) {
            throw BusinessException(ErrorCode.EMPTY_CART)
        }

        val buyer = memberJpaRepository.findById(buyerId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        // 상품 상태 검증 (판매 중인지 확인)
        cart.cartItems.forEach { item ->
            if (item.product.status != ProductStatus.ON_SALE) {
                throw BusinessException(ErrorCode.PRODUCT_NOT_AVAILABLE)
            }
        }

        // 2. 재고 원자적 감소 (실패 시 롤백)
        val decreasedProducts = mutableListOf<Pair<Long, Int>>()
        val sellerIds = mutableSetOf<Long>()

        try {
            cart.cartItems.forEach { cartItem ->
                val updated = productJpaRepository.decreaseStockAtomically(
                    cartItem.product.id!!,
                    cartItem.quantity
                )
                if (updated == 0) {
                    log.warn("Failed to decrease stock for product ${cartItem.product.id}")
                    throw BusinessException(ErrorCode.INSUFFICIENT_STOCK)
                }
                decreasedProducts.add(cartItem.product.id!! to cartItem.quantity)
                sellerIds.add(cartItem.product.seller.id!!)
            }

            // 3. 결제 처리 (Mock - 항상 성공)
            val paymentResult = processPayment(buyerId, cart.cartItems.sumOf {
                it.product.price.multiply(it.quantity.toBigDecimal())
            })

            if (!paymentResult) {
                throw BusinessException(ErrorCode.PAYMENT_FAILED)
            }

            // 4. Order/OrderItem 생성
            val order = Order(
                buyer = buyer,
                shippingAddress = ShippingAddress(
                    zipCode = req.zipCode,
                    address = req.address,
                    addressDetail = req.addressDetail,
                    receiverName = req.receiverName,
                    receiverPhone = req.receiverPhone
                )
            )

            cart.cartItems.forEach { cartItem ->
                val orderItem = OrderItem(
                    product = cartItem.product,
                    seller = cartItem.product.seller,
                    productName = cartItem.product.name,
                    productPrice = cartItem.product.price,
                    quantity = cartItem.quantity,
                    subtotal = cartItem.product.price.multiply(cartItem.quantity.toBigDecimal())
                )
                order.addItem(orderItem)
            }

            val savedOrder = orderJpaRepository.save(order)

            // 5. 장바구니 비우기
            cart.clear()
            cartJpaRepository.save(cart)

            // 6. 이벤트 발행
            sellerIds.forEach { sellerId ->
                eventPublisher.publishEvent(OrderCreatedEvent(savedOrder.id!!, sellerId))
            }

            // Outbox에 저장 (Kafka로 비동기 발행)
            outboxEventService.saveEvent(
                aggregateType = "Order",
                aggregateId = savedOrder.id.toString(),
                eventType = "OrderCreated",
                payload = mapOf(
                    "orderId" to savedOrder.id,
                    "buyerId" to buyerId,
                    "sellerIds" to sellerIds.toList(),
                    "totalAmount" to savedOrder.totalAmount,
                    "orderNumber" to savedOrder.orderNumber
                )
            )

            return CheckoutResponse.success(OrderResponse.from(savedOrder))

        } catch (e: BusinessException) {
            // 재고 롤백
            decreasedProducts.forEach { (productId, quantity) ->
                productJpaRepository.restoreStockAtomically(productId, quantity)
            }
            throw e
        }
    }

    /**
     * Mock 결제 처리 - 항상 성공 반환
     * 실제 결제 API 연동 시 이 부분을 구현
     */
    private fun processPayment(buyerId: Long, amount: java.math.BigDecimal): Boolean {
        log.info("Processing payment for buyer $buyerId, amount: $amount (Mock - always success)")
        return true
    }

    private fun checkoutFallback(buyerId: Long, req: CheckoutRequest, ex: Throwable): CheckoutResponse {
        log.error("Circuit breaker fallback triggered for checkout. Buyer: $buyerId, Error: ${ex.message}")
        throw BusinessException(ErrorCode.SERVICE_UNAVAILABLE)
    }
}
