package com.example.marketplace.order.dto

import com.example.marketplace.order.Order
import com.example.marketplace.order.OrderItem
import java.math.BigDecimal
import java.time.LocalDateTime

data class OrderResponse(
    val id: Long,
    val orderNumber: String,
    val buyerId: Long,
    val buyerName: String?,
    val status: String,
    val totalAmount: BigDecimal,
    val shippingAddress: ShippingAddressResponse,
    val orderItems: List<OrderItemResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(order: Order) = OrderResponse(
            id = order.id!!,
            orderNumber = order.orderNumber,
            buyerId = order.buyer.id!!,
            buyerName = order.buyer.name,
            status = order.status.name,
            totalAmount = order.totalAmount,
            shippingAddress = ShippingAddressResponse(
                zipCode = order.shippingAddress.zipCode,
                address = order.shippingAddress.address,
                addressDetail = order.shippingAddress.addressDetail,
                receiverName = order.shippingAddress.receiverName,
                receiverPhone = order.shippingAddress.receiverPhone
            ),
            orderItems = order.orderItems.map { OrderItemResponse.from(it) },
            createdAt = order.createdAt,
            updatedAt = order.updatedAt
        )
    }
}

data class ShippingAddressResponse(
    val zipCode: String,
    val address: String,
    val addressDetail: String?,
    val receiverName: String,
    val receiverPhone: String
)

data class OrderItemResponse(
    val id: Long,
    val productId: Long,
    val sellerId: Long,
    val productName: String,
    val productPrice: BigDecimal,
    val quantity: Int,
    val subtotal: BigDecimal
) {
    companion object {
        fun from(item: OrderItem) = OrderItemResponse(
            id = item.id!!,
            productId = item.product.id!!,
            sellerId = item.seller.id!!,
            productName = item.productName,
            productPrice = item.productPrice,
            quantity = item.quantity,
            subtotal = item.subtotal
        )
    }
}
