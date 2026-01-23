package com.example.marketplace.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.*

interface OrderJpaRepository : JpaRepository<Order, Long>, OrderJpaRepositoryCustom {

    fun findByBuyerIdOrderByCreatedAtDesc(buyerId: Long, pageable: Pageable): Page<Order>

    fun findByOrderNumber(orderNumber: String): Optional<Order>
}
