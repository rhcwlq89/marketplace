package com.example.marketplace.order

import com.querydsl.jpa.impl.JPAQueryFactory
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.support.PageableExecutionUtils
import org.springframework.transaction.annotation.Transactional

class OrderJpaRepositoryImpl(
    private val queryFactory: JPAQueryFactory
) : OrderJpaRepositoryCustom {

    private val order = QOrder.order
    private val orderItem = QOrderItem.orderItem

    @Transactional(readOnly = true)
    override fun findBySellerId(sellerId: Long, pageable: Pageable): Page<Order> {
        val content = queryFactory
            .selectDistinct(order)
            .from(order)
            .join(order.orderItems, orderItem)
            .where(orderItem.seller.id.eq(sellerId))
            .orderBy(order.createdAt.desc())
            .offset(pageable.offset)
            .limit(pageable.pageSize.toLong())
            .fetch()

        val countQuery = queryFactory
            .select(order.countDistinct())
            .from(order)
            .join(order.orderItems, orderItem)
            .where(orderItem.seller.id.eq(sellerId))

        return PageableExecutionUtils.getPage(content, pageable) {
            countQuery.fetchOne() ?: 0L
        }
    }
}
