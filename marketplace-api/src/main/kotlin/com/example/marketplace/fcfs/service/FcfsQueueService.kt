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

    @Scheduled(fixedDelay = 3000)
    fun processQueue() {
        val productId = TARGET_PRODUCT_ID
        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"

        val users = redisTemplate.opsForZSet().popMin(queueKey, 10)
        if (users.isNullOrEmpty()) return

        for (typedTuple in users) {
            val userId = typedTuple.value ?: continue
            redisTemplate.opsForSet().add(allowedKey, userId)

            val event = mapOf("productId" to productId, "userId" to userId.toLong())
            kafkaTemplate.send(KafkaConfig.FCFS_QUEUE_ORDERS_TOPIC, userId, event)
        }

        log.info("[FCFS Queue] {}명 진입 허용", users.size)
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

        val completedKey = "$COMPLETED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().add(completedKey, userId.toString())

        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().remove(allowedKey, userId.toString())
    }
}
