package com.example.marketplace.fcfs.service

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.entity.FcfsOrder
import com.example.marketplace.fcfs.entity.FcfsStrategy
import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcfsRedisService(
    private val redisTemplate: StringRedisTemplate,
    private val fcfsStockDecrementScript: DefaultRedisScript<Long>,
    private val fcfsOrderRepository: FcfsOrderRepository
) {

    @Transactional
    fun purchase(request: FcfsOrderRequest): FcfsOrderResponse {
        val stockKey = "fcfs:stock:${request.productId}"
        val purchasedKey = "fcfs:purchased:${request.productId}"

        val result = redisTemplate.execute(
            fcfsStockDecrementScript,
            listOf(stockKey, purchasedKey),
            request.userId.toString(),
            request.quantity.toString()
        )

        return when (result) {
            -1L -> FcfsOrderResponse(orderId = 0, status = "ALREADY_PURCHASED", message = "이미 구매함")
            -2L -> FcfsOrderResponse(orderId = 0, status = "SOLD_OUT", message = "재고 소진")
            else -> {
                val order = fcfsOrderRepository.save(
                    FcfsOrder(
                        productId = request.productId,
                        userId = request.userId,
                        strategy = FcfsStrategy.REDIS
                    )
                )
                FcfsOrderResponse(orderId = order.id, status = "SUCCESS")
            }
        }
    }
}
