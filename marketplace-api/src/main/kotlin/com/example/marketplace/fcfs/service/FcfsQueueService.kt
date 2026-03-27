package com.example.marketplace.fcfs.service

import com.example.marketplace.config.KafkaConfig
import com.example.marketplace.fcfs.dto.FcfsQueueStatusResponse
import com.example.marketplace.fcfs.entity.FcfsOrder
import com.example.marketplace.fcfs.entity.FcfsStrategy
import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import com.example.marketplace.product.ProductJpaRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcfsQueueService(
    private val redisTemplate: StringRedisTemplate,
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val productJpaRepository: ProductJpaRepository,
    private val fcfsOrderRepository: FcfsOrderRepository
) {
    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        const val QUEUE_KEY_PREFIX = "fcfs:queue:"
        const val ALLOWED_KEY_PREFIX = "fcfs:allowed:"
        const val COMPLETED_KEY_PREFIX = "fcfs:completed:"
        const val TARGET_PRODUCT_ID = 1L
    }

    fun enter(productId: Long, userId: Long): FcfsQueueStatusResponse {
        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val score = System.currentTimeMillis().toDouble()

        val existingRank = redisTemplate.opsForZSet().rank(queueKey, userId.toString())
        if (existingRank != null) {
            return FcfsQueueStatusResponse(status = "WAITING", position = existingRank)
        }

        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        if (redisTemplate.opsForSet().isMember(allowedKey, userId.toString()) == true) {
            return FcfsQueueStatusResponse(status = "ALLOWED")
        }

        redisTemplate.opsForZSet().add(queueKey, userId.toString(), score)
        val rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString()) ?: 0
        return FcfsQueueStatusResponse(status = "WAITING", position = rank)
    }

    fun getStatus(productId: Long, userId: Long): FcfsQueueStatusResponse {
        val completedKey = "$COMPLETED_KEY_PREFIX$productId"
        if (redisTemplate.opsForSet().isMember(completedKey, userId.toString()) == true) {
            return FcfsQueueStatusResponse(status = "COMPLETED")
        }

        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        if (redisTemplate.opsForSet().isMember(allowedKey, userId.toString()) == true) {
            return FcfsQueueStatusResponse(status = "ALLOWED")
        }

        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString())
        return if (rank != null) {
            FcfsQueueStatusResponse(status = "WAITING", position = rank)
        } else {
            FcfsQueueStatusResponse(status = "NOT_IN_QUEUE")
        }
    }

    /**
     * 스케줄러: 3초마다 10명씩 대기열에서 꺼내 허용
     * ZPOPMIN + SADD를 Lua 스크립트로 원자적으로 실행하여
     * queue에서 빠졌지만 allowed에 아직 안 들어간 과도 상태를 방지한다.
     */
    @Scheduled(fixedDelay = 3000)
    fun processQueue() {
        val productId = TARGET_PRODUCT_ID
        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"

        // Lua: ZPOPMIN → SADD를 원자적으로 실행, 이동된 userId 목록 반환
        val script = """
            local queueKey = KEYS[1]
            local allowedKey = KEYS[2]
            local count = tonumber(ARGV[1])
            local moved = {}
            for i = 1, count do
                local result = redis.call('ZPOPMIN', queueKey)
                if #result == 0 then break end
                local userId = result[1]
                redis.call('SADD', allowedKey, userId)
                table.insert(moved, userId)
            end
            return moved
        """.trimIndent()

        val result = redisTemplate.execute(
            org.springframework.data.redis.core.script.DefaultRedisScript<List<*>>(script, List::class.java),
            listOf(queueKey, allowedKey),
            "10"
        )

        val movedUsers = result?.filterIsInstance<String>() ?: return
        if (movedUsers.isEmpty()) return

        for (userId in movedUsers) {
            val event = mapOf("productId" to productId, "userId" to userId.toLong())
            kafkaTemplate.send(KafkaConfig.FCFS_QUEUE_ORDERS_TOPIC, userId, event)
        }

        log.info("[FCFS Queue] {}명 진입 허용", movedUsers.size)
    }

    @KafkaListener(
        topics = [KafkaConfig.FCFS_QUEUE_ORDERS_TOPIC],
        groupId = "fcfs-queue-group"
    )
    @Transactional
    fun consumeQueueOrder(event: Map<String, Any>) {
        val productId = (event["productId"] as Number).toLong()
        val userId = (event["userId"] as Number).toLong()

        val updated = productJpaRepository.decreaseStockAtomically(productId, 1)
        if (updated > 0) {
            fcfsOrderRepository.save(
                FcfsOrder(
                    productId = productId,
                    userId = userId,
                    strategy = FcfsStrategy.QUEUE
                )
            )
        }

        // 재고 차감 성공/실패 무관하게 처리 완료 표시
        // k6에서는 COMPLETED 상태로 폴링 종료
        val completedKey = "$COMPLETED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().add(completedKey, userId.toString())

        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().remove(allowedKey, userId.toString())

        if (updated > 0) {
            log.info("[FCFS Queue] 주문 성공: userId={}", userId)
        } else {
            log.info("[FCFS Queue] 재고 소진: userId={}", userId)
        }
    }
}
