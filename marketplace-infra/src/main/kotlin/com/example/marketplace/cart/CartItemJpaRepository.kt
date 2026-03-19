package com.example.marketplace.cart

import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface CartItemJpaRepository : JpaRepository<CartItem, Long> {
    fun findByCartIdAndProductId(cartId: Long, productId: Long): Optional<CartItem>
    fun deleteByCartIdAndProductId(cartId: Long, productId: Long): Int
}
