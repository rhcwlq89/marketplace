package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import com.example.marketplace.product.ProductJpaRepository
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
class FcfsResetController(
    private val productJpaRepository: ProductJpaRepository,
    private val fcfsOrderRepository: FcfsOrderRepository,
    private val redisTemplate: StringRedisTemplate
) {

    @PostMapping("/api/fcfs/reset")
    @Transactional
    fun reset(
        @RequestParam productId: Long,
        @RequestParam(defaultValue = "100") stock: Int
    ): ResponseEntity<Map<String, String>> {
        // 1. 재고 리셋
        val product = productJpaRepository.findById(productId).orElse(null)
        if (product != null) {
            val diff = stock - product.stockQuantity
            if (diff > 0) {
                product.restoreStock(diff)
            } else if (diff < 0) {
                product.decreaseStock(-diff)
            }
        }

        // 2. Redis 키 삭제
        val prefixes = listOf(
            "fcfs:stock:", "fcfs:purchased:",
            "fcfs:queue:", "fcfs:allowed:", "fcfs:completed:",
            "fcfs:quota:", "fcfs:issued:", "fcfs:used:"
        )
        for (prefix in prefixes) {
            val keys = redisTemplate.keys("$prefix*")
            if (!keys.isNullOrEmpty()) {
                redisTemplate.delete(keys)
            }
        }

        // 3. Redis에 재고 세팅 (Redis 방식용)
        redisTemplate.opsForValue().set("fcfs:stock:$productId", stock.toString())

        // 4. Redis에 쿼터 세팅 (토큰 방식용)
        redisTemplate.opsForValue().set("fcfs:quota:$productId", stock.toString())

        // 5. FcfsOrder 전체 삭제
        fcfsOrderRepository.deleteAllFcfsOrders()

        return ResponseEntity.ok(
            mapOf("status" to "RESET", "productId" to productId.toString(), "stock" to stock.toString())
        )
    }
}
