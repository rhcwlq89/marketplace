package com.example.marketplace.config

import org.redisson.Redisson
import org.redisson.api.RedissonClient
import org.redisson.config.Config
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

@Configuration
@Profile("docker", "prod")
class RedissonConfig {

    @Value("\${spring.data.redis.host:localhost}")
    private lateinit var redisHost: String

    @Value("\${spring.data.redis.port:6379}")
    private var redisPort: Int = 6379

    @Value("\${spring.data.redis.password:}")
    private lateinit var redisPassword: String

    @Bean
    fun redissonClient(): RedissonClient {
        val config = Config()
        val address = "redis://$redisHost:$redisPort"

        config.useSingleServer().apply {
            setAddress(address)
            if (redisPassword.isNotBlank()) {
                setPassword(redisPassword)
            }
            connectionMinimumIdleSize = 5
            connectionPoolSize = 10
            idleConnectionTimeout = 10000
            connectTimeout = 10000
            timeout = 3000
            retryAttempts = 3
            retryInterval = 1500
        }

        return Redisson.create(config)
    }
}
