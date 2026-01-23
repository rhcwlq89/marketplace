package com.example.marketplace.product.dto

import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank
import java.math.BigDecimal

data class CreateProductRequest(
    @field:NotBlank
    val name: String,

    val description: String? = null,

    @field:DecimalMin("0")
    val price: BigDecimal,

    @field:Min(0)
    val stockQuantity: Int = 0,

    val categoryId: Long? = null,

    val status: String = "DRAFT"
)
