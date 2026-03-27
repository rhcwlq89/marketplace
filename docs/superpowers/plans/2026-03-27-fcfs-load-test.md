# 선착순 4가지 방식 부하 테스트 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**목표:** 선착순 재고 차감 4가지 전략(DB 락, Redis Lua, 대기열, 토큰)을 구현하고 k6 부하 테스트로 실측 데이터를 수집한다.

**아키텍처:** `com.example.marketplace.fcfs` 패키지에 격리 구현. 기존 코드 수정 없음. 각 전략은 독립적인 Controller + Service를 가지며, 경량 FcfsOrder 엔티티로 주문을 기록한다. Product 엔티티는 기존 것을 재사용한다.

**기술 스택:** Spring Boot 3.2, Spring Data JPA (Pessimistic Lock), Spring Data Redis (Lua Script), Spring Kafka, jjwt 0.11.5, k6

---

## 파일 구조

### 새로 생성하는 파일

```
marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/
├── dto/
│   ├── FcfsOrderRequest.kt
│   ├── FcfsOrderResponse.kt
│   ├── FcfsTokenResponse.kt
│   └── FcfsQueueStatusResponse.kt
├── entity/
│   └── FcfsOrder.kt
├── repository/
│   └── FcfsOrderRepository.kt
├── service/
│   ├── FcfsDbLockService.kt
│   ├── FcfsRedisService.kt
│   ├── FcfsQueueService.kt
│   └── FcfsTokenService.kt
├── controller/
│   ├── FcfsDbLockController.kt
│   ├── FcfsRedisController.kt
│   ├── FcfsQueueController.kt
│   ├── FcfsTokenController.kt
│   └── FcfsResetController.kt
└── config/
    └── FcfsConfig.kt

marketplace-api/src/main/resources/
└── scripts/
    └── fcfs_stock_decrement.lua

k6/
├── test-db-lock.js
├── test-redis.js
├── test-queue.js
└── test-token.js
```

### 수정하는 파일

- `marketplace-api/src/main/kotlin/com/example/marketplace/config/KafkaConfig.kt` — fcfs.queue.orders 토픽 추가

---

## Task 1: 엔티티 + DTO + Repository

**Files:**
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/entity/FcfsOrder.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/repository/FcfsOrderRepository.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsOrderRequest.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsOrderResponse.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsTokenResponse.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsQueueStatusResponse.kt`

- [ ] **Step 1: FcfsOrder 엔티티 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/entity/FcfsOrder.kt
package com.example.marketplace.fcfs.entity

import jakarta.persistence.*
import java.time.LocalDateTime

@Entity
@Table(name = "fcfs_orders")
class FcfsOrder(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    @Column(nullable = false)
    val productId: Long,

    @Column(nullable = false)
    val userId: Long,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val strategy: FcfsStrategy,

    @Column(nullable = false)
    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class FcfsStrategy {
    DB_LOCK, REDIS, QUEUE, TOKEN
}
```

- [ ] **Step 2: FcfsOrderRepository 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/repository/FcfsOrderRepository.kt
package com.example.marketplace.fcfs.repository

import com.example.marketplace.fcfs.entity.FcfsOrder
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query

interface FcfsOrderRepository : JpaRepository<FcfsOrder, Long> {

    @Modifying
    @Query("DELETE FROM FcfsOrder")
    fun deleteAllFcfsOrders()
}
```

- [ ] **Step 3: DTO 4개 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsOrderRequest.kt
package com.example.marketplace.fcfs.dto

data class FcfsOrderRequest(
    val productId: Long,
    val userId: Long,
    val quantity: Int = 1
)
```

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsOrderResponse.kt
package com.example.marketplace.fcfs.dto

data class FcfsOrderResponse(
    val orderId: Long,
    val status: String,
    val message: String? = null
)
```

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsTokenResponse.kt
package com.example.marketplace.fcfs.dto

data class FcfsTokenResponse(
    val token: String
)
```

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/dto/FcfsQueueStatusResponse.kt
package com.example.marketplace.fcfs.dto

data class FcfsQueueStatusResponse(
    val status: String,   // WAITING, ALLOWED, NOT_IN_QUEUE
    val position: Long? = null
)
```

- [ ] **Step 4: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/
git commit -m "feat(fcfs): 엔티티, DTO, Repository 추가"
```

---

## Task 2: DB 락 방식 구현

**Files:**
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsDbLockService.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsDbLockController.kt`

- [ ] **Step 1: FcfsDbLockService 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsDbLockService.kt
package com.example.marketplace.fcfs.service

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.entity.FcfsOrder
import com.example.marketplace.fcfs.entity.FcfsStrategy
import com.example.marketplace.fcfs.repository.FcfsOrderRepository
import com.example.marketplace.product.ProductJpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FcfsDbLockService(
    private val productJpaRepository: ProductJpaRepository,
    private val fcfsOrderRepository: FcfsOrderRepository
) {

    @Transactional
    fun purchase(request: FcfsOrderRequest): FcfsOrderResponse {
        // SELECT FOR UPDATE — 행 락 획득
        val product = productJpaRepository.findByIdWithLock(request.productId)
            .orElseThrow { IllegalArgumentException("상품을 찾을 수 없습니다: ${request.productId}") }

        if (product.stockQuantity < request.quantity) {
            return FcfsOrderResponse(orderId = 0, status = "SOLD_OUT", message = "재고 소진")
        }

        product.decreaseStock(request.quantity)

        val order = fcfsOrderRepository.save(
            FcfsOrder(
                productId = request.productId,
                userId = request.userId,
                strategy = FcfsStrategy.DB_LOCK
            )
        )

        return FcfsOrderResponse(orderId = order.id, status = "SUCCESS")
    }
}
```

- [ ] **Step 2: FcfsDbLockController 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsDbLockController.kt
package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.service.FcfsDbLockService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class FcfsDbLockController(
    private val fcfsDbLockService: FcfsDbLockService
) {

    @PostMapping("/api/orders/db-lock")
    fun purchase(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsOrderResponse> {
        val response = fcfsDbLockService.purchase(request)
        return if (response.status == "SUCCESS") {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).body(response)
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsDbLockService.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsDbLockController.kt
git commit -m "feat(fcfs): DB 락 방식 구현 (SELECT FOR UPDATE)"
```

---

## Task 3: Redis Lua 스크립트 방식 구현

**Files:**
- Create: `marketplace-api/src/main/resources/scripts/fcfs_stock_decrement.lua`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/config/FcfsConfig.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsRedisService.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsRedisController.kt`

- [ ] **Step 1: Lua 스크립트 생성**

```lua
-- marketplace-api/src/main/resources/scripts/fcfs_stock_decrement.lua
local stockKey = KEYS[1]
local purchasedKey = KEYS[2]
local userId = ARGV[1]
local quantity = tonumber(ARGV[2])

-- 중복 구매 확인
if redis.call('SISMEMBER', purchasedKey, userId) == 1 then
    return -1
end

-- 재고 차감
local remaining = redis.call('DECRBY', stockKey, quantity)
if remaining < 0 then
    redis.call('INCRBY', stockKey, quantity)
    return -2
end

-- 구매 기록
redis.call('SADD', purchasedKey, userId)
return remaining
```

- [ ] **Step 2: FcfsConfig — Lua 스크립트 빈 등록**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/config/FcfsConfig.kt
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
```

- [ ] **Step 3: FcfsRedisService 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsRedisService.kt
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
```

- [ ] **Step 4: FcfsRedisController 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsRedisController.kt
package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.service.FcfsRedisService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController

@RestController
class FcfsRedisController(
    private val fcfsRedisService: FcfsRedisService
) {

    @PostMapping("/api/orders/redis")
    fun purchase(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsOrderResponse> {
        val response = fcfsRedisService.purchase(request)
        return if (response.status == "SUCCESS") {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).body(response)
        }
    }
}
```

- [ ] **Step 5: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/resources/scripts/fcfs_stock_decrement.lua
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/config/FcfsConfig.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsRedisService.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsRedisController.kt
git commit -m "feat(fcfs): Redis Lua 스크립트 방식 구현"
```

---

## Task 4: 대기열 방식 구현

**Files:**
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsQueueService.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsQueueController.kt`
- Modify: `marketplace-api/src/main/kotlin/com/example/marketplace/config/KafkaConfig.kt` — 토픽 상수 추가

- [ ] **Step 1: KafkaConfig에 토픽 추가**

`marketplace-api/src/main/kotlin/com/example/marketplace/config/KafkaConfig.kt` 에서 companion object 안에 상수를 추가하고, `fcfsQueueOrdersTopic()` 빈을 추가한다.

기존 companion object에 추가:
```kotlin
const val FCFS_QUEUE_ORDERS_TOPIC = "fcfs.queue.orders"
```

기존 토픽 빈들 아래에 추가:
```kotlin
@Bean
fun fcfsQueueOrdersTopic(): NewTopic {
    return TopicBuilder.name(FCFS_QUEUE_ORDERS_TOPIC)
        .partitions(3)
        .replicas(1)
        .build()
}
```

- [ ] **Step 2: FcfsQueueService 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsQueueService.kt
package com.example.marketplace.fcfs.service

import com.example.marketplace.config.KafkaConfig
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
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
        // 테스트 대상 productId — 단일 상품 테스트이므로 고정
        const val TARGET_PRODUCT_ID = 1L
    }

    /**
     * 대기열 진입
     */
    fun enter(productId: Long, userId: Long): FcfsQueueStatusResponse {
        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val score = System.currentTimeMillis().toDouble()

        // 이미 대기열에 있으면 현재 순번 반환
        val existingRank = redisTemplate.opsForZSet().rank(queueKey, userId.toString())
        if (existingRank != null) {
            return FcfsQueueStatusResponse(status = "WAITING", position = existingRank)
        }

        // 이미 허용된 사용자인지 확인
        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        if (redisTemplate.opsForSet().isMember(allowedKey, userId.toString()) == true) {
            return FcfsQueueStatusResponse(status = "ALLOWED")
        }

        redisTemplate.opsForZSet().add(queueKey, userId.toString(), score)
        val rank = redisTemplate.opsForZSet().rank(queueKey, userId.toString()) ?: 0
        return FcfsQueueStatusResponse(status = "WAITING", position = rank)
    }

    /**
     * 대기열 상태 조회
     */
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
     */
    @Scheduled(fixedDelay = 3000)
    fun processQueue() {
        val productId = TARGET_PRODUCT_ID
        val queueKey = "$QUEUE_KEY_PREFIX$productId"
        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"

        // 대기열에서 10명 꺼냄
        val users = redisTemplate.opsForZSet().popMin(queueKey, 10)
        if (users.isNullOrEmpty()) return

        for (typedTuple in users) {
            val userId = typedTuple.value ?: continue
            redisTemplate.opsForSet().add(allowedKey, userId)

            // Kafka로 주문 이벤트 발행
            val event = mapOf("productId" to productId, "userId" to userId.toLong())
            kafkaTemplate.send(KafkaConfig.FCFS_QUEUE_ORDERS_TOPIC, userId, event)
        }

        log.info("[FCFS Queue] {}명 진입 허용", users.size)
    }

    /**
     * Kafka Consumer: 주문 생성
     */
    @KafkaListener(
        topics = [KafkaConfig.FCFS_QUEUE_ORDERS_TOPIC],
        groupId = "fcfs-queue-group"
    )
    @Transactional
    fun consumeQueueOrder(event: Map<String, Any>) {
        val productId = (event["productId"] as Number).toLong()
        val userId = (event["userId"] as Number).toLong()

        // 재고 차감 (기존 atomic 패턴 활용)
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

        // 완료 표시
        val completedKey = "$COMPLETED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().add(completedKey, userId.toString())

        // allowed에서 제거
        val allowedKey = "$ALLOWED_KEY_PREFIX$productId"
        redisTemplate.opsForSet().remove(allowedKey, userId.toString())
    }
}
```

- [ ] **Step 3: FcfsQueueController 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsQueueController.kt
package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsQueueStatusResponse
import com.example.marketplace.fcfs.service.FcfsQueueService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class FcfsQueueController(
    private val fcfsQueueService: FcfsQueueService
) {

    @PostMapping("/api/queue/enter")
    fun enter(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsQueueStatusResponse> {
        val response = fcfsQueueService.enter(request.productId, request.userId)
        return ResponseEntity.ok(response)
    }

    @GetMapping("/api/queue/status")
    fun status(
        @RequestParam productId: Long,
        @RequestParam userId: Long
    ): ResponseEntity<FcfsQueueStatusResponse> {
        val response = fcfsQueueService.getStatus(productId, userId)
        return ResponseEntity.ok(response)
    }
}
```

- [ ] **Step 4: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsQueueService.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsQueueController.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/config/KafkaConfig.kt
git commit -m "feat(fcfs): 대기열 방식 구현 (Redis Sorted Set + Kafka)"
```

---

## Task 5: 토큰 방식 구현

**Files:**
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsTokenService.kt`
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsTokenController.kt`

- [ ] **Step 1: FcfsTokenService 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsTokenService.kt
package com.example.marketplace.fcfs.service

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
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
        private const val TOKEN_VALIDITY_MS = 5 * 60 * 1000L // 5분
    }

    // FCFS 토큰 전용 서명 키 (앱 내 고정)
    private val signingKey: SecretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256)

    /**
     * 토큰 발급: Redis 쿼터 차감 → JWT 생성
     */
    fun issueToken(productId: Long, userId: Long): FcfsTokenResponse? {
        val quotaKey = "$QUOTA_KEY_PREFIX$productId"
        val issuedKey = "$ISSUED_KEY_PREFIX$productId"

        // 중복 발급 확인
        if (redisTemplate.opsForSet().isMember(issuedKey, userId.toString()) == true) {
            return null // ALREADY_ISSUED
        }

        // 쿼터 차감
        val remaining = redisTemplate.opsForValue().decrement(quotaKey) ?: -1
        if (remaining < 0) {
            redisTemplate.opsForValue().increment(quotaKey) // 롤백
            return null // SOLD_OUT
        }

        // 발급 기록
        redisTemplate.opsForSet().add(issuedKey, userId.toString())

        // JWT 생성
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

    /**
     * 토큰으로 구매: JWT 검증 → 중복 사용 확인 → 주문 저장
     */
    @Transactional
    fun purchaseWithToken(token: String, quantity: Int): FcfsOrderResponse {
        // JWT 파싱
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

        // 중복 사용 확인
        val usedKey = "$USED_KEY_PREFIX$productId"
        val added = redisTemplate.opsForSet().add(usedKey, userId.toString())
        if (added == null || added == 0L) {
            return FcfsOrderResponse(orderId = 0, status = "TOKEN_ALREADY_USED", message = "이미 사용된 토큰")
        }

        // 주문 저장
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
```

- [ ] **Step 2: FcfsTokenController 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsTokenController.kt
package com.example.marketplace.fcfs.controller

import com.example.marketplace.fcfs.dto.FcfsOrderRequest
import com.example.marketplace.fcfs.dto.FcfsOrderResponse
import com.example.marketplace.fcfs.dto.FcfsTokenResponse
import com.example.marketplace.fcfs.service.FcfsTokenService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
class FcfsTokenController(
    private val fcfsTokenService: FcfsTokenService
) {

    @PostMapping("/api/tokens/issue")
    fun issueToken(@RequestBody request: FcfsOrderRequest): ResponseEntity<FcfsTokenResponse> {
        val response = fcfsTokenService.issueToken(request.productId, request.userId)
        return if (response != null) {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).build()
        }
    }

    @PostMapping("/api/orders/token")
    fun purchaseWithToken(
        @RequestHeader("Authorization") authorization: String,
        @RequestBody body: Map<String, Int>
    ): ResponseEntity<FcfsOrderResponse> {
        val token = authorization.removePrefix("Bearer ").trim()
        val quantity = body["quantity"] ?: 1

        val response = fcfsTokenService.purchaseWithToken(token, quantity)
        return if (response.status == "SUCCESS") {
            ResponseEntity.ok(response)
        } else {
            ResponseEntity.status(409).body(response)
        }
    }
}
```

- [ ] **Step 3: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/service/FcfsTokenService.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsTokenController.kt
git commit -m "feat(fcfs): 토큰 방식 구현 (JWT + Redis 쿼터)"
```

---

## Task 6: 리셋 엔드포인트 + Security 설정

**Files:**
- Create: `marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsResetController.kt`
- Modify: Security 설정에서 `/api/orders/db-lock`, `/api/orders/redis`, `/api/queue/**`, `/api/tokens/**`, `/api/orders/token`, `/api/fcfs/**` 경로를 permitAll로 추가

- [ ] **Step 1: FcfsResetController 생성**

```kotlin
// marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsResetController.kt
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
            product.restoreStock(stock - product.stockQuantity)
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

        return ResponseEntity.ok(mapOf("status" to "RESET", "productId" to productId.toString(), "stock" to stock.toString()))
    }
}
```

- [ ] **Step 2: Security 설정에서 FCFS 경로 permitAll 추가**

`marketplace-api/src/main/kotlin/com/example/marketplace/config/SecurityConfig.kt` (또는 유사 파일)을 찾아서 HttpSecurity 설정에 다음 경로를 permitAll로 추가:

```kotlin
.requestMatchers("/api/orders/db-lock", "/api/orders/redis", "/api/orders/token").permitAll()
.requestMatchers("/api/queue/**").permitAll()
.requestMatchers("/api/tokens/**").permitAll()
.requestMatchers("/api/fcfs/**").permitAll()
```

> **참고:** 기존 SecurityConfig 파일의 정확한 위치와 형식에 맞춰서 추가해야 한다. `requestMatchers` 체인에 위 경로를 추가한다.

- [ ] **Step 3: 컴파일 확인**

Run: `cd /Users/ihojong/Documents/code/marketplace && ./gradlew :marketplace-api:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/controller/FcfsResetController.kt
git add marketplace-api/src/main/kotlin/com/example/marketplace/config/SecurityConfig.kt
git commit -m "feat(fcfs): 리셋 엔드포인트 + Security permitAll 설정"
```

---

## Task 7: 통합 동작 확인

인프라를 기동하고 4가지 엔드포인트가 모두 작동하는지 수동으로 확인한다.

- [ ] **Step 1: 인프라 기동**

```bash
cd /Users/ihojong/Documents/code/marketplace
docker compose up -d mysql redis kafka zookeeper
```

약 10초 대기 후 컨테이너 상태 확인:
```bash
docker compose ps
```
Expected: mysql, redis, kafka, zookeeper 모두 running/healthy

- [ ] **Step 2: 앱 실행**

```bash
cd /Users/ihojong/Documents/code/marketplace
SPRING_PROFILES_ACTIVE=docker \
MYSQL_HOST=localhost \
REDIS_HOST=localhost \
KAFKA_BOOTSTRAP_SERVERS=localhost:9092 \
JWT_SECRET=fcfs-test-secret-key-must-be-at-least-32-bytes-long \
./gradlew :marketplace-api:bootRun
```

Expected: `Started MarketplaceApiApplicationKt` 로그 확인

- [ ] **Step 3: 테스트용 상품 확인/생성**

productId=1인 상품이 있는지 확인. 없으면 SQL로 직접 생성:

```bash
docker exec -i marketplace-mysql-1 mysql -umarketplace -pmarketplace123 marketplace -e "
SELECT id, name, stock_quantity, status FROM products WHERE id = 1;
"
```

상품이 없으면:
```bash
docker exec -i marketplace-mysql-1 mysql -umarketplace -pmarketplace123 marketplace -e "
INSERT INTO products (id, name, description, price, stock_quantity, status, sales_count, version, created_at, updated_at)
VALUES (1, 'FCFS 테스트 상품', '부하 테스트용', 10000, 100, 'ON_SALE', 0, 0, NOW(), NOW());
"
```

- [ ] **Step 4: 리셋 + DB 락 테스트**

```bash
# 리셋
curl -s -X POST "http://localhost:8080/api/fcfs/reset?productId=1&stock=100"

# DB 락 구매
curl -s -X POST http://localhost:8080/api/orders/db-lock \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"userId":1,"quantity":1}'
```
Expected: `{"orderId":1,"status":"SUCCESS",...}`

- [ ] **Step 5: 리셋 + Redis 테스트**

```bash
curl -s -X POST "http://localhost:8080/api/fcfs/reset?productId=1&stock=100"

curl -s -X POST http://localhost:8080/api/orders/redis \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"userId":1,"quantity":1}'
```
Expected: `{"orderId":...,"status":"SUCCESS",...}`

- [ ] **Step 6: 리셋 + 대기열 테스트**

```bash
curl -s -X POST "http://localhost:8080/api/fcfs/reset?productId=1&stock=100"

# 대기열 진입
curl -s -X POST http://localhost:8080/api/queue/enter \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"userId":1,"quantity":1}'

# 3초 뒤 상태 확인 (ALLOWED 또는 COMPLETED가 나와야 함)
sleep 5
curl -s "http://localhost:8080/api/queue/status?productId=1&userId=1"
```
Expected: `{"status":"COMPLETED"}` 또는 `{"status":"ALLOWED"}`

- [ ] **Step 7: 리셋 + 토큰 테스트**

```bash
curl -s -X POST "http://localhost:8080/api/fcfs/reset?productId=1&stock=100"

# 토큰 발급
TOKEN=$(curl -s -X POST http://localhost:8080/api/tokens/issue \
  -H "Content-Type: application/json" \
  -d '{"productId":1,"userId":1,"quantity":1}' | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")

echo "Token: $TOKEN"

# 토큰으로 구매
curl -s -X POST http://localhost:8080/api/orders/token \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"quantity":1}'
```
Expected: `{"orderId":...,"status":"SUCCESS",...}`

- [ ] **Step 8: 커밋 (필요시 수정사항)**

동작 확인 중 발견된 문제를 수정하고 커밋:
```bash
cd /Users/ihojong/Documents/code/marketplace
git add -A
git commit -m "fix(fcfs): 통합 동작 확인 후 수정"
```

---

## Task 8: k6 테스트 스크립트 작성

**Files:**
- Create: `k6/test-db-lock.js`
- Create: `k6/test-redis.js`
- Create: `k6/test-queue.js`
- Create: `k6/test-token.js`

- [ ] **Step 1: test-db-lock.js**

```javascript
// k6/test-db-lock.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successCount = new Counter('success_count');
const failCount = new Counter('fail_count');
const purchaseTime = new Trend('purchase_time');

export const options = {
    scenarios: {
        spike: {
            executor: 'shared-iterations',
            vus: __ENV.VUS ? parseInt(__ENV.VUS) : 100,
            iterations: __ENV.ITERATIONS ? parseInt(__ENV.ITERATIONS) : 100,
            maxDuration: '60s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    // 리셋
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting DB Lock test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const payload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/orders/db-lock`, payload, params);
    const elapsed = Date.now() - start;

    purchaseTime.add(elapsed);

    if (res.status === 200) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }

    check(res, {
        'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
}
```

- [ ] **Step 2: test-redis.js**

```javascript
// k6/test-redis.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successCount = new Counter('success_count');
const failCount = new Counter('fail_count');
const purchaseTime = new Trend('purchase_time');

export const options = {
    scenarios: {
        spike: {
            executor: 'shared-iterations',
            vus: __ENV.VUS ? parseInt(__ENV.VUS) : 100,
            iterations: __ENV.ITERATIONS ? parseInt(__ENV.ITERATIONS) : 100,
            maxDuration: '60s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting Redis test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const payload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const params = { headers: { 'Content-Type': 'application/json' } };

    const start = Date.now();
    const res = http.post(`${BASE_URL}/api/orders/redis`, payload, params);
    const elapsed = Date.now() - start;

    purchaseTime.add(elapsed);

    if (res.status === 200) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }

    check(res, {
        'status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
}
```

- [ ] **Step 3: test-queue.js**

```javascript
// k6/test-queue.js
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successCount = new Counter('success_count');
const failCount = new Counter('fail_count');
const purchaseTime = new Trend('purchase_time');

export const options = {
    scenarios: {
        spike: {
            executor: 'shared-iterations',
            vus: __ENV.VUS ? parseInt(__ENV.VUS) : 100,
            iterations: __ENV.ITERATIONS ? parseInt(__ENV.ITERATIONS) : 100,
            maxDuration: '120s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting Queue test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const start = Date.now();

    // Phase 1: 대기열 진입
    const enterPayload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const params = { headers: { 'Content-Type': 'application/json' } };
    const enterRes = http.post(`${BASE_URL}/api/queue/enter`, enterPayload, params);

    check(enterRes, { 'enter ok': (r) => r.status === 200 });

    // Phase 2: 폴링 — COMPLETED될 때까지 대기
    let completed = false;
    for (let i = 0; i < 60; i++) {
        const statusRes = http.get(
            `${BASE_URL}/api/queue/status?productId=1&userId=${userId}`
        );

        if (statusRes.status === 200) {
            const body = JSON.parse(statusRes.body);
            if (body.status === 'COMPLETED') {
                completed = true;
                break;
            }
            if (body.status === 'NOT_IN_QUEUE') {
                break;
            }
        }
        sleep(1);
    }

    const elapsed = Date.now() - start;
    purchaseTime.add(elapsed);

    if (completed) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }
}
```

- [ ] **Step 4: test-token.js**

```javascript
// k6/test-token.js
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const successCount = new Counter('success_count');
const failCount = new Counter('fail_count');
const purchaseTime = new Trend('purchase_time');

export const options = {
    scenarios: {
        spike: {
            executor: 'shared-iterations',
            vus: __ENV.VUS ? parseInt(__ENV.VUS) : 100,
            iterations: __ENV.ITERATIONS ? parseInt(__ENV.ITERATIONS) : 100,
            maxDuration: '60s',
        },
    },
};

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export function setup() {
    const resetRes = http.post(`${BASE_URL}/api/fcfs/reset?productId=1&stock=100`);
    check(resetRes, { 'reset ok': (r) => r.status === 200 });
    console.log('Reset complete. Starting Token test...');
}

export default function () {
    const userId = __VU * 10000 + __ITER;
    const params = { headers: { 'Content-Type': 'application/json' } };
    const start = Date.now();

    // Phase 1: 토큰 발급
    const issuePayload = JSON.stringify({ productId: 1, userId: userId, quantity: 1 });
    const issueRes = http.post(`${BASE_URL}/api/tokens/issue`, issuePayload, params);

    if (issueRes.status !== 200) {
        failCount.add(1);
        purchaseTime.add(Date.now() - start);
        return;
    }

    const token = JSON.parse(issueRes.body).token;

    // Phase 2: 토큰으로 구매
    const orderRes = http.post(
        `${BASE_URL}/api/orders/token`,
        JSON.stringify({ quantity: 1 }),
        {
            headers: {
                'Content-Type': 'application/json',
                'Authorization': `Bearer ${token}`,
            },
        }
    );

    const elapsed = Date.now() - start;
    purchaseTime.add(elapsed);

    if (orderRes.status === 200) {
        successCount.add(1);
    } else {
        failCount.add(1);
    }

    check(orderRes, {
        'order status is 200 or 409': (r) => r.status === 200 || r.status === 409,
    });
}
```

- [ ] **Step 5: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
git add k6/test-db-lock.js k6/test-redis.js k6/test-queue.js k6/test-token.js
git commit -m "feat(fcfs): k6 부하 테스트 스크립트 4종 추가"
```

---

## Task 9: 부하 테스트 실행 + 결과 수집

앱이 실행 중인 상태에서 k6 테스트를 순차적으로 실행한다.

- [ ] **Step 1: k6 설치 확인**

```bash
k6 version
```
Expected: `k6 v0.49+` 또는 유사 버전

없으면:
```bash
brew install k6
```

- [ ] **Step 2: JVM 워밍업 (각 방식 2회씩)**

```bash
cd /Users/ihojong/Documents/code/marketplace

# DB 락 워밍업
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-db-lock.js
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-db-lock.js

# Redis 워밍업
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-redis.js
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-redis.js

# 대기열 워밍업
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-queue.js

# 토큰 워밍업
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-token.js
k6 run -e VUS=10 -e ITERATIONS=10 k6/test-token.js
```

결과는 버린다.

- [ ] **Step 3: DB 락 — 100/500/1000명 테스트**

```bash
# 100명
k6 run -e VUS=100 -e ITERATIONS=100 k6/test-db-lock.js 2>&1 | tee results/db-lock-100.txt

# 500명
k6 run -e VUS=500 -e ITERATIONS=500 k6/test-db-lock.js 2>&1 | tee results/db-lock-500.txt

# 1000명
k6 run -e VUS=1000 -e ITERATIONS=1000 k6/test-db-lock.js 2>&1 | tee results/db-lock-1000.txt
```

각 실행 후 k6 요약에서 기록할 항목:
- `http_req_duration` → avg, p(99)
- `iteration_duration` → 총 소요 시간
- `success_count`, `fail_count`
- `http_reqs` → rate (TPS 근사)
- `purchase_time` → avg, p(99)

- [ ] **Step 4: Redis — 100/500/1000명 테스트**

```bash
k6 run -e VUS=100 -e ITERATIONS=100 k6/test-redis.js 2>&1 | tee results/redis-100.txt
k6 run -e VUS=500 -e ITERATIONS=500 k6/test-redis.js 2>&1 | tee results/redis-500.txt
k6 run -e VUS=1000 -e ITERATIONS=1000 k6/test-redis.js 2>&1 | tee results/redis-1000.txt
```

- [ ] **Step 5: 대기열 — 100/500/1000명 테스트**

```bash
k6 run -e VUS=100 -e ITERATIONS=100 k6/test-queue.js 2>&1 | tee results/queue-100.txt
k6 run -e VUS=500 -e ITERATIONS=500 k6/test-queue.js 2>&1 | tee results/queue-500.txt
k6 run -e VUS=1000 -e ITERATIONS=1000 k6/test-queue.js 2>&1 | tee results/queue-1000.txt
```

> 대기열은 maxDuration이 120s이므로 넉넉하지만, 1000명은 ~5분 걸릴 수 있다.

- [ ] **Step 6: 토큰 — 100/500/1000명 테스트**

```bash
k6 run -e VUS=100 -e ITERATIONS=100 k6/test-token.js 2>&1 | tee results/token-100.txt
k6 run -e VUS=500 -e ITERATIONS=500 k6/test-token.js 2>&1 | tee results/token-500.txt
k6 run -e VUS=1000 -e ITERATIONS=1000 k6/test-token.js 2>&1 | tee results/token-1000.txt
```

- [ ] **Step 7: 결과 정리**

12개 테스트 결과를 아래 형식의 표로 정리:

```
| 측정 항목 | DB 락 | Redis | 대기열 | 토큰 |
|----------|-------|-------|--------|------|
| 총 소요 시간 | | | | |
| 평균 응답 시간 | | | | |
| P99 응답 시간 | | | | |
| 성공 | | | | |
| 실패 (품절) | | | | |
| TPS | | | | |
```

동시 100명, 500명, 1000명 각각에 대해 위 표를 만든다.

- [ ] **Step 8: 커밋**

```bash
cd /Users/ihojong/Documents/code/marketplace
mkdir -p results
git add results/
git commit -m "test(fcfs): k6 부하 테스트 결과 (100/500/1000명)"
```

---

## Task 10: 블로그 글 실측 데이터로 업데이트

**Files:**
- Modify: `/Users/ihojong/Documents/code/rhcwlq89/src/content/blog/fcfs-load-test-comparison.md`
- Create: `/Users/ihojong/Documents/code/rhcwlq89/src/content/blog/en/fcfs-load-test-comparison.md` (없으면 생성)

- [ ] **Step 1: 한국어 블로그 3절 (테스트 결과) 교체**

Task 9에서 수집한 실측 데이터로 3.1, 3.2, 3.3절의 표를 전부 교체.

- [ ] **Step 2: 한국어 블로그 4절 (결과 분석) 교체**

TPS 비교 그래프, P99 비교 그래프, DB 커넥션 사용 패턴을 실측 데이터로 교체. ASCII 그래프의 비율도 실측에 맞게 조정.

- [ ] **Step 3: 한국어 블로그 5~6절 검토**

추가 측정(CPU, 메모리)과 비용 분석은 실측 TPS 기반으로 재계산.

- [ ] **Step 4: 한국어 블로그 7절 (상황별 최적 방식) 검토**

실측 결과가 기존 결론과 다르면 분석/추천을 수정.

- [ ] **Step 5: 한국어 블로그 정리(마무리)절 교체**

요약 표를 실측 데이터로 교체.

- [ ] **Step 6: k6 테스트 스크립트 섹션 업데이트**

블로그 2절의 k6 스크립트를 실제 사용한 스크립트로 교체.

- [ ] **Step 7: 영어 버전 동기화**

한국어 블로그의 변경 사항을 영어 버전에도 반영.

- [ ] **Step 8: 커밋**

```bash
cd /Users/ihojong/Documents/code/rhcwlq89
git add src/content/blog/fcfs-load-test-comparison.md
git add src/content/blog/en/fcfs-load-test-comparison.md
git commit -m "fix: 선착순 부하 테스트 결과를 실측 데이터로 교체"
```
