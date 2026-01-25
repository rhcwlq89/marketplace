package com.example.marketplace.order

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.DistributedLock
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.order.dto.CreateOrderRequest
import com.example.marketplace.order.dto.OrderResponse
import com.example.marketplace.order.dto.UpdateOrderStatusRequest
import com.example.marketplace.order.event.OrderCreatedEvent
import com.example.marketplace.order.event.OrderStatusChangedEvent
import com.example.marketplace.outbox.OutboxEventService
import com.example.marketplace.product.ProductJpaRepository
import io.github.resilience4j.bulkhead.annotation.Bulkhead
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class OrderService(
    private val orderJpaRepository: OrderJpaRepository,
    private val productJpaRepository: ProductJpaRepository,
    private val memberJpaRepository: MemberJpaRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val outboxEventService: OutboxEventService
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    @DistributedLock(key = "'order:create:' + #buyerId", waitTime = 5, leaseTime = 30)
    @CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
    @Bulkhead(name = "orderService")
    @Retry(name = "orderService")
    fun createOrder(buyerId: Long, req: CreateOrderRequest): OrderResponse {
        if (req.orderItems.isEmpty()) {
            throw BusinessException(ErrorCode.EMPTY_ORDER_ITEMS)
        }

        val buyer = memberJpaRepository.findById(buyerId)
            .orElseThrow { BusinessException(ErrorCode.MEMBER_NOT_FOUND) }

        val order = Order(
            buyer = buyer,
            shippingAddress = ShippingAddress(
                zipCode = req.shippingAddress.zipCode,
                address = req.shippingAddress.address,
                addressDetail = req.shippingAddress.addressDetail,
                receiverName = req.shippingAddress.receiverName,
                receiverPhone = req.shippingAddress.receiverPhone
            )
        )

        val sellerIds = mutableSetOf<Long>()
        val decreasedProducts = mutableListOf<Pair<Long, Int>>()

        try {
            req.orderItems.forEach { itemReq ->
                val product = productJpaRepository.findById(itemReq.productId)
                    .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

                val updated = productJpaRepository.decreaseStockAtomically(itemReq.productId, itemReq.quantity)
                if (updated == 0) {
                    log.warn("Failed to decrease stock for product ${itemReq.productId}")
                    throw BusinessException(ErrorCode.INSUFFICIENT_STOCK)
                }
                decreasedProducts.add(itemReq.productId to itemReq.quantity)

                val orderItem = OrderItem(
                    product = product,
                    seller = product.seller,
                    productName = product.name,
                    productPrice = product.price,
                    quantity = itemReq.quantity,
                    subtotal = product.price.multiply(itemReq.quantity.toBigDecimal())
                )
                order.addItem(orderItem)
                sellerIds.add(product.seller.id!!)
            }
        } catch (e: BusinessException) {
            decreasedProducts.forEach { (productId, quantity) ->
                productJpaRepository.restoreStockAtomically(productId, quantity)
            }
            throw e
        }

        val savedOrder = orderJpaRepository.save(order)

        // 로컬 이벤트 발행 (동기 처리용)
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

        return OrderResponse.from(savedOrder)
    }

    fun getOrder(memberId: Long, orderId: Long): OrderResponse {
        val order = orderJpaRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }

        val isBuyer = order.buyer.id == memberId
        val isSeller = order.orderItems.any { it.seller.id == memberId }

        if (!isBuyer && !isSeller) {
            throw BusinessException(ErrorCode.ORDER_NOT_OWNED)
        }

        return OrderResponse.from(order)
    }

    fun getMyOrders(buyerId: Long, pageable: Pageable): Page<OrderResponse> {
        return orderJpaRepository.findByBuyerIdOrderByCreatedAtDesc(buyerId, pageable)
            .map { OrderResponse.from(it) }
    }

    @Transactional
    @DistributedLock(key = "'order:cancel:' + #orderId", waitTime = 5, leaseTime = 30)
    fun cancelOrder(buyerId: Long, orderId: Long): OrderResponse {
        val order = orderJpaRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }

        if (order.buyer.id != buyerId) {
            throw BusinessException(ErrorCode.ORDER_NOT_OWNED)
        }

        if (!order.canBeCancelled()) {
            throw BusinessException(ErrorCode.CANNOT_CANCEL_ORDER)
        }

        order.orderItems.forEach { item ->
            productJpaRepository.restoreStockAtomically(item.product.id!!, item.quantity)
        }

        order.cancel()
        val savedOrder = orderJpaRepository.save(order)

        val sellerIds = order.orderItems.map { it.seller.id!! }.toSet()

        // 로컬 이벤트 발행
        sellerIds.forEach { sellerId ->
            eventPublisher.publishEvent(OrderStatusChangedEvent(savedOrder.id!!, sellerId, buyerId, "CANCELLED"))
        }

        // Outbox에 저장 (Kafka로 비동기 발행)
        outboxEventService.saveEvent(
            aggregateType = "Order",
            aggregateId = savedOrder.id.toString(),
            eventType = "OrderStatusChanged",
            payload = mapOf(
                "orderId" to savedOrder.id,
                "buyerId" to buyerId,
                "sellerIds" to sellerIds.toList(),
                "status" to "CANCELLED",
                "orderNumber" to savedOrder.orderNumber
            )
        )

        return OrderResponse.from(savedOrder)
    }

    fun getSellerOrders(sellerId: Long, pageable: Pageable): Page<OrderResponse> {
        return orderJpaRepository.findBySellerId(sellerId, pageable)
            .map { OrderResponse.from(it) }
    }

    @Transactional
    fun updateOrderStatus(sellerId: Long, orderId: Long, req: UpdateOrderStatusRequest): OrderResponse {
        val order = orderJpaRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }

        val isSeller = order.orderItems.any { it.seller.id == sellerId }
        if (!isSeller) {
            throw BusinessException(ErrorCode.ORDER_NOT_OWNED)
        }

        val newStatus = try {
            OrderStatus.valueOf(req.status)
        } catch (e: IllegalArgumentException) {
            throw BusinessException(ErrorCode.INVALID_ORDER_STATUS)
        }

        order.updateStatus(newStatus)
        val savedOrder = orderJpaRepository.save(order)

        // 로컬 이벤트 발행
        eventPublisher.publishEvent(
            OrderStatusChangedEvent(savedOrder.id!!, sellerId, order.buyer.id!!, newStatus.name)
        )

        // Outbox에 저장 (Kafka로 비동기 발행)
        outboxEventService.saveEvent(
            aggregateType = "Order",
            aggregateId = savedOrder.id.toString(),
            eventType = "OrderStatusChanged",
            payload = mapOf(
                "orderId" to savedOrder.id,
                "buyerId" to order.buyer.id,
                "sellerId" to sellerId,
                "status" to newStatus.name,
                "orderNumber" to savedOrder.orderNumber
            )
        )

        return OrderResponse.from(savedOrder)
    }

    private fun createOrderFallback(buyerId: Long, req: CreateOrderRequest, ex: Throwable): OrderResponse {
        log.error("Circuit breaker fallback triggered for createOrder. Buyer: $buyerId, Error: ${ex.message}")
        throw BusinessException(ErrorCode.SERVICE_UNAVAILABLE)
    }
}
