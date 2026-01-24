package com.example.marketplace.product

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.LocalDateTime
import java.util.*

interface ProductJpaRepositoryCustom {

    fun findByIdWithLock(id: Long): Optional<Product>

    fun search(
        keyword: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        status: ProductStatus?,
        sellerId: Long?,
        pageable: Pageable
    ): Page<Product>

    fun decreaseStockAtomically(productId: Long, quantity: Int): Int

    fun restoreStockAtomically(productId: Long, quantity: Int): Int

    fun searchWithCursor(
        keyword: String?,
        categoryId: Long?,
        minPrice: BigDecimal?,
        maxPrice: BigDecimal?,
        status: ProductStatus?,
        sellerId: Long?,
        cursor: LocalDateTime?,
        cursorId: Long?,
        limit: Int
    ): List<Product>
}
