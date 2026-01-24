package com.example.marketplace.security

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.data.redis.core.RedisTemplate
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

interface TokenBlacklistService {
    fun blacklist(token: String, expirationTimeMs: Long)
    fun isBlacklisted(token: String): Boolean
}

@Service
@Profile("docker", "prod")
class RedisTokenBlacklistService(
    private val redisTemplate: RedisTemplate<String, Any>
) : TokenBlacklistService {

    private val log = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val BLACKLIST_PREFIX = "token:blacklist:"
    }

    override fun blacklist(token: String, expirationTimeMs: Long) {
        val key = BLACKLIST_PREFIX + token
        val ttlSeconds = (expirationTimeMs / 1000).coerceAtLeast(1)

        try {
            redisTemplate.opsForValue().set(key, "blacklisted", ttlSeconds, TimeUnit.SECONDS)
            log.debug("Token blacklisted with TTL: $ttlSeconds seconds")
        } catch (e: Exception) {
            log.error("Failed to blacklist token in Redis", e)
        }
    }

    override fun isBlacklisted(token: String): Boolean {
        val key = BLACKLIST_PREFIX + token
        return try {
            redisTemplate.hasKey(key)
        } catch (e: Exception) {
            log.error("Failed to check token blacklist in Redis", e)
            false
        }
    }
}

@Service
@Profile("local")
class InMemoryTokenBlacklistService : TokenBlacklistService {

    private val log = LoggerFactory.getLogger(javaClass)
    private val blacklist = ConcurrentHashMap<String, Long>()

    override fun blacklist(token: String, expirationTimeMs: Long) {
        val expirationTime = System.currentTimeMillis() + expirationTimeMs
        blacklist[token] = expirationTime
        log.debug("Token blacklisted until: $expirationTime")

        cleanup()
    }

    override fun isBlacklisted(token: String): Boolean {
        val expirationTime = blacklist[token] ?: return false

        if (System.currentTimeMillis() > expirationTime) {
            blacklist.remove(token)
            return false
        }

        return true
    }

    private fun cleanup() {
        val now = System.currentTimeMillis()
        blacklist.entries.removeIf { it.value < now }
    }
}
