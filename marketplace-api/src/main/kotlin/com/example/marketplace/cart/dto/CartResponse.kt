package com.example.marketplace.cart.dto

import com.example.marketplace.cart.Cart
import com.example.marketplace.cart.CartItem
import java.math.BigDecimal
import java.time.LocalDateTime

data class CartResponse(
    val id: Long,
    val memberId: Long,
    val items: List<CartItemResponse>,
    val totalAmount: BigDecimal,
    val totalQuantity: Int,
    val updatedAt: LocalDateTime
) {
    companion object {
        fun from(cart: Cart): CartResponse {
            val items = cart.cartItems.map { CartItemResponse.from(it) }
            return CartResponse(
                id = cart.id!!,
                memberId = cart.member.id!!,
                items = items,
                totalAmount = items.sumOf { it.subtotal },
                totalQuantity = items.sumOf { it.quantity },
                updatedAt = cart.updatedAt
            )
        }
    }
}

data class CartItemResponse(
    val id: Long,
    val productId: Long,
    val productName: String,
    val productPrice: BigDecimal,
    val productStatus: String,
    val sellerId: Long,
    val sellerName: String?,
    val quantity: Int,
    val subtotal: BigDecimal,
    val stockQuantity: Int,
    val imageUrl: String?
) {
    companion object {
        fun from(cartItem: CartItem): CartItemResponse {
            val product = cartItem.product
            return CartItemResponse(
                id = cartItem.id!!,
                productId = product.id!!,
                productName = product.name,
                productPrice = product.price,
                productStatus = product.status.name,
                sellerId = product.seller.id!!,
                sellerName = product.seller.name,
                quantity = cartItem.quantity,
                subtotal = product.price.multiply(BigDecimal(cartItem.quantity)),
                stockQuantity = product.stockQuantity,
                imageUrl = product.images.firstOrNull()?.imageUrl
            )
        }
    }
}
