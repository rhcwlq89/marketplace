package com.example.marketplace.fcfs.service

import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.dto.FcfsTokenResponse
import com.example.marketplace.fcfs.entity.FcfsOrder
import com.example.marketplace.fcfs.entity.FcfsStrategy
import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.security.Keys
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.*
import javax.crypto.SecretKey

@Service
class FcfsTokenService(
    private val redisTemplate: StringRedisTemplate,
    private val fcfsOrderRepository: FcfsOrderRepository
) {

    companion object {
        const val QUOTA_KEY_PREFIX = "fcfs:quota:"
        const val ISSUED_KEY_PREFIX = "fcfs:issued:"
        const val USED_KEY_PREFIX = "fcfs:used:"
        private const val TOKEN_VALIDITY_MS = 5 * 60 * 1000L
    }

    private val signingKey: SecretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256)

    fun issueToken(productId: Long, userId: Long): FcfsTokenResponse? {
        val quotaKey = "$QUOTA_KEY_PREFIX$productId"
        val issuedKey = "$ISSUED_KEY_PREFIX$productId"

        if (redisTemplate.opsForSet().isMember(issuedKey, userId.toString()) == true) {
            return null
        }

        val remaining = redisTemplate.opsForValue().decrement(quotaKey) ?: -1
        if (remaining < 0) {
            redisTemplate.opsForValue().increment(quotaKey)
            return null
        }

        redisTemplate.opsForSet().add(issuedKey, userId.toString())

        val now = Date()
        val token = Jwts.builder()
            .setSubject(userId.toString())
            .claim("productId", productId)
            .claim("userId", userId)
            .setIssuedAt(now)
            .setExpiration(Date(now.time + TOKEN_VALIDITY_MS))
            .signWith(signingKey)
            .compact()

        return FcfsTokenResponse(token = token)
    }

    @Transactional
    fun purchaseWithToken(token: String, quantity: Int): FcfsOrderResponse {
        val claims = try {
            Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .body
        } catch (e: Exception) {
            return FcfsOrderResponse(orderId = 0, status = "INVALID_TOKEN", message = "토큰이 유효하지 않습니다")
        }

        val productId = (claims["productId"] as Number).toLong()
        val userId = (claims["userId"] as Number).toLong()

        val usedKey = "$USED_KEY_PREFIX$productId"
        val added = redisTemplate.opsForSet().add(usedKey, userId.toString())
        if (added == null || added == 0L) {
            return FcfsOrderResponse(orderId = 0, status = "TOKEN_ALREADY_USED", message = "이미 사용된 토큰")
        }

        val order = fcfsOrderRepository.save(
            FcfsOrder(
                productId = productId,
                userId = userId,
                strategy = FcfsStrategy.TOKEN
            )
        )

        return FcfsOrderResponse(orderId = order.id, status = "SUCCESS")
    }
}
