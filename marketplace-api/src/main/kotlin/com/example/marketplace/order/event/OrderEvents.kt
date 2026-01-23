package com.example.marketplace.order.event

data class OrderCreatedEvent(
    val orderId: Long,
    val sellerId: Long
)

data class OrderStatusChangedEvent(
    val orderId: Long,
    val sellerId: Long,
    val buyerId: Long,
    val newStatus: String
)
