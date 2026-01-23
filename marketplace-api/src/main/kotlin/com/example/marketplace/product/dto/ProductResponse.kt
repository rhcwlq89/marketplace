package com.example.marketplace.product.dto

import com.example.marketplace.product.Product
import java.math.BigDecimal
import java.time.LocalDateTime

data class ProductResponse(
    val id: Long,
    val sellerId: Long,
    val sellerName: String?,
    val categoryId: Long?,
    val categoryName: String?,
    val name: String,
    val description: String?,
    val price: BigDecimal,
    val stockQuantity: Int,
    val status: String,
    val salesCount: Int,
    val images: List<ProductImageResponse>,
    val createdAt: LocalDateTime,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(product: Product) = ProductResponse(
            id = product.id!!,
            sellerId = product.seller.id!!,
            sellerName = product.seller.name,
            categoryId = product.category?.id,
            categoryName = product.category?.name,
            name = product.name,
            description = product.description,
            price = product.price,
            stockQuantity = product.stockQuantity,
            status = product.status.name,
            salesCount = product.salesCount,
            images = product.images.map { ProductImageResponse.from(it) },
            createdAt = product.createdAt,
            updatedAt = product.updatedAt
        )
    }
}

data class ProductImageResponse(
    val id: Long,
    val imageUrl: String,
    val displayOrder: Int
) {
    companion object {
        fun from(image: com.example.marketplace.product.ProductImage) = ProductImageResponse(
            id = image.id!!,
            imageUrl = image.imageUrl,
            displayOrder = image.displayOrder
        )
    }
}
