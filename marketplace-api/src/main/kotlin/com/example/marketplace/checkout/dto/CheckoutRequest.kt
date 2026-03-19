package com.example.marketplace.checkout.dto

import com.example.marketplace.order.dto.OrderResponse
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class CheckoutRequest(
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

data class CheckoutResponse(
    val order: OrderResponse,
    val paymentStatus: String,
    val message: String
) {
    companion object {
        fun success(order: OrderResponse) = CheckoutResponse(
            order = order,
            paymentStatus = "SUCCESS",
            message = "Payment completed successfully"
        )
    }
}
