package com.example.marketplace.order.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotEmpty

data class CreateOrderRequest(
    @field:NotEmpty
    @field:Valid
    val orderItems: List<OrderItemRequest>,

    @field:Valid
    val shippingAddress: ShippingAddressRequest
)

data class OrderItemRequest(
    val productId: Long,

    @field:Min(1)
    val quantity: Int
)

data class ShippingAddressRequest(
    @field:NotBlank
    val zipCode: String,

    @field:NotBlank
    val address: String,

    val addressDetail: String?,

    @field:NotBlank
    val receiverName: String,

    @field:NotBlank
    val receiverPhone: String
)
