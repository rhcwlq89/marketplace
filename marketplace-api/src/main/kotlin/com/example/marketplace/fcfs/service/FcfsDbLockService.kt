package com.example.marketplace.fcfs.service

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.entity.FcfsOrder
import com.example.marketplace.fcfs.entity.FcfsStrategy
import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import com.example.marketplace.product.ProductJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcfsDbLockService(
    private val productJpaRepository: ProductJpaRepository,
    private val fcfsOrderRepository: FcfsOrderRepository
) {

    @Transactional
    fun purchase(request: FcfsOrderRequest): FcfsOrderResponse {
        val product = productJpaRepository.findByIdWithLock(request.productId)
            .orElseThrow { IllegalArgumentException("상품을 찾을 수 없습니다: ${request.productId}") }

        if (product.stockQuantity < request.quantity) {
            return FcfsOrderResponse(orderId = 0, status = "SOLD_OUT", message = "재고 소진")
        }

        product.decreaseStock(request.quantity)

        val order = fcfsOrderRepository.save(
            FcfsOrder(
                productId = request.productId,
                userId = request.userId,
                strategy = FcfsStrategy.DB_LOCK
            )
        )

        return FcfsOrderResponse(orderId = order.id, status = "SUCCESS")
    }
}
