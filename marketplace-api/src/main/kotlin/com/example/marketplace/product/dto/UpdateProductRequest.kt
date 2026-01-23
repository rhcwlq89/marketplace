package com.example.marketplace.product.dto

import java.math.BigDecimal

data class UpdateProductRequest(
    val name: String? = null,
    val description: String? = null,
    val price: BigDecimal? = null,
    val stockQuantity: Int? = null,
    val categoryId: Long? = null,
    val status: String? = null
)
