package com.example.marketplace.config

import org.redisson.api.RedissonClient
import org.slf4j.LoggerFactory
import org.springframework.boot.actuate.health.Health
import org.springframework.boot.actuate.health.HealthIndicator
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

@Component
@Profile("docker", "prod")
class RedisHealthIndicator(
    private val redisConnectionFactory: RedisConnectionFactory
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun health(): Health {
        return try {
            val connection = redisConnectionFactory.connection
            val pong = connection.ping()
            connection.close()

            if (pong != null) {
                Health.up()
                    .withDetail("status", "Redis is available")
                    .withDetail("response", pong)
                    .build()
            } else {
                Health.down()
                    .withDetail("status", "Redis ping returned null")
                    .build()
            }
        } catch (e: Exception) {
            log.error("Redis health check failed", e)
            Health.down(e)
                .withDetail("status", "Redis is unavailable")
                .withDetail("error", e.message)
                .build()
        }
    }
}

@Component
@Profile("docker", "prod")
class RedissonHealthIndicator(
    private val redissonClient: RedissonClient
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun health(): Health {
        return try {
            val isConnected = !redissonClient.isShutdown
            val nodesCount = redissonClient.nodesGroup.nodes.size

            if (isConnected) {
                Health.up()
                    .withDetail("status", "Redisson is connected")
                    .withDetail("nodes", nodesCount)
                    .build()
            } else {
                Health.down()
                    .withDetail("status", "Redisson is shutdown")
                    .build()
            }
        } catch (e: Exception) {
            log.error("Redisson health check failed", e)
            Health.down(e)
                .withDetail("status", "Redisson is unavailable")
                .withDetail("error", e.message)
                .build()
        }
    }
}

@Component
@Profile("docker", "prod")
class KafkaHealthIndicator(
    private val kafkaTemplate: KafkaTemplate<String, Any>
) : HealthIndicator {

    private val log = LoggerFactory.getLogger(javaClass)

    override fun health(): Health {
        return try {
            val producerFactory = kafkaTemplate.producerFactory

            Health.up()
                .withDetail("status", "Kafka producer is initialized")
                .build()
        } catch (e: Exception) {
            log.error("Kafka health check failed", e)
            Health.down(e)
                .withDetail("status", "Kafka is unavailable")
                .withDetail("error", e.message)
                .build()
        }
    }
}
