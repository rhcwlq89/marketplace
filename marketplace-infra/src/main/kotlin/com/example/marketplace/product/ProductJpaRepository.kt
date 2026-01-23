package com.example.marketplace.product

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

interface ProductJpaRepository : JpaRepository<Product, Long>, ProductJpaRepositoryCustom {

    fun findByStatusIn(statuses: List<ProductStatus>, pageable: Pageable): Page<Product>

    fun findBySellerId(sellerId: Long, pageable: Pageable): Page<Product>

    fun findByStatusOrderBySalesCountDesc(status: ProductStatus, pageable: Pageable): List<Product>
}
