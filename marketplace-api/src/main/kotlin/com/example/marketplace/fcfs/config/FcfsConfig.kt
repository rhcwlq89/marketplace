package com.example.marketplace.fcfs.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.scripting.support.ResourceScriptSource

@Configuration
class FcfsConfig {

    @Bean
    fun fcfsStockDecrementScript(): DefaultRedisScript<Long> {
        val script = DefaultRedisScript<Long>()
        script.setScriptSource(ResourceScriptSource(ClassPathResource("scripts/fcfs_stock_decrement.lua")))
        script.resultType = Long::class.java
        return script
    }
}
