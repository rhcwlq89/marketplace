package com.example.marketplace.order

import com.example.marketplace.common.BusinessException
import com.example.marketplace.common.ErrorCode
import com.example.marketplace.member.MemberJpaRepository
import com.example.marketplace.order.dto.CreateOrderRequest
import com.example.marketplace.order.dto.OrderResponse
import com.example.marketplace.order.dto.UpdateOrderStatusRequest
import com.example.marketplace.order.event.OrderCreatedEvent
import com.example.marketplace.order.event.OrderStatusChangedEvent
import com.example.marketplace.product.ProductJpaRepository
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
    private val eventPublisher: ApplicationEventPublisher
) {

    @Transactional
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

        req.orderItems.forEach { itemReq ->
            val product = productJpaRepository.findByIdWithLock(itemReq.productId)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

            product.decreaseStock(itemReq.quantity)
            productJpaRepository.save(product)

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

        val savedOrder = orderJpaRepository.save(order)

        // Publish event for each seller
        sellerIds.forEach { sellerId ->
            eventPublisher.publishEvent(OrderCreatedEvent(savedOrder.id!!, sellerId))
        }

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
    fun cancelOrder(buyerId: Long, orderId: Long): OrderResponse {
        val order = orderJpaRepository.findById(orderId)
            .orElseThrow { BusinessException(ErrorCode.ORDER_NOT_FOUND) }

        if (order.buyer.id != buyerId) {
            throw BusinessException(ErrorCode.ORDER_NOT_OWNED)
        }

        if (!order.canBeCancelled()) {
            throw BusinessException(ErrorCode.CANNOT_CANCEL_ORDER)
        }

        // Restore stock
        order.orderItems.forEach { item ->
            val product = productJpaRepository.findByIdWithLock(item.product.id!!)
                .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
            product.restoreStock(item.quantity)
            productJpaRepository.save(product)
        }

        order.cancel()
        val savedOrder = orderJpaRepository.save(order)

        // Publish event
        order.orderItems.map { it.seller.id!! }.toSet().forEach { sellerId ->
            eventPublisher.publishEvent(OrderStatusChangedEvent(savedOrder.id!!, sellerId, buyerId, "CANCELLED"))
        }

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

        // Publish event to buyer
        eventPublisher.publishEvent(
            OrderStatusChangedEvent(savedOrder.id!!, sellerId, order.buyer.id!!, newStatus.name)
        )

        return OrderResponse.from(savedOrder)
    }
}
