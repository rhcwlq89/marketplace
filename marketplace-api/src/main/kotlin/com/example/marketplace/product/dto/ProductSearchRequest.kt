package com.example.marketplace.product.dto

import java.math.BigDecimal

data class ProductSearchRequest(
    val keyword: String? = null,
    val categoryId: Long? = null,
    val minPrice: BigDecimal? = null,
    val maxPrice: BigDecimal? = null,
    val status: String? = null,
    val sellerId: Long? = null
)
