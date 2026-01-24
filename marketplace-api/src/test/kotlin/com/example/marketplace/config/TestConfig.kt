package com.example.marketplace.config

import io.github.resilience4j.ratelimiter.RateLimiter
import io.github.resilience4j.ratelimiter.RateLimiterConfig
import io.github.resilience4j.ratelimiter.RateLimiterRegistry
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Duration

@TestConfiguration
class TestConfig {

    @Bean
    @Primary
    fun testRateLimiterRegistry(): RateLimiterRegistry {
        val config = RateLimiterConfig.custom()
            .limitForPeriod(1000)
            .limitRefreshPeriod(Duration.ofSeconds(1))
            .timeoutDuration(Duration.ZERO)
            .build()

        val registry = RateLimiterRegistry.of(config)
        registry.rateLimiter("default")
        registry.rateLimiter("orderCreation")

        return registry
    }
}
