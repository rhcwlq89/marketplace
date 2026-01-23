package com.example.marketplace.order.dto

import jakarta.validation.constraints.NotBlank

data class UpdateOrderStatusRequest(
    @field:NotBlank
    val status: String
)
