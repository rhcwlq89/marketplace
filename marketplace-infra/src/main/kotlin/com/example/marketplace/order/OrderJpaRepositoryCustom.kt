package com.example.marketplace.order

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable

interface OrderJpaRepositoryCustom {

    fun findBySellerId(sellerId: Long, pageable: Pageable): Page<Order>
}
