package com.example.marketplace.cart.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min

data class AddCartItemRequest(
    val productId: Long,

    @field:Min(1)
    @field:Max(99)
    val quantity: Int = 1
)

data class UpdateCartItemRequest(
    @field:Min(1)
    @field:Max(99)
    val quantity: Int
)
