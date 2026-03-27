# FCFS 4-Strategy Load Test — Design Spec

## Goal

Implement 4 different first-come-first-served (FCFS) stock deduction strategies in the marketplace project and run k6 load tests under identical conditions (stock: 100, VUs: 100/500/1,000). Replace the blog post's estimated numbers with real measured data.

## Constraints

- All new code goes in `com.example.marketplace.fcfs` package — existing code untouched
- Test environment: MySQL/Redis/Kafka in Docker Compose, Spring Boot app runs locally
- k6 runs on host machine targeting `localhost:8080`
- Blog endpoints must match exactly: `/api/orders/db-lock`, `/api/orders/redis`, `/api/queue/enter`, `/api/tokens/issue`, `/api/orders/token`

## Architecture

```
marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/
├── controller/
│   ├── FcfsDbLockController.kt        # POST /api/orders/db-lock
│   ├── FcfsRedisController.kt         # POST /api/orders/redis
│   ├── FcfsQueueController.kt         # POST /api/queue/enter, GET /api/queue/status
│   └── FcfsTokenController.kt         # POST /api/tokens/issue, POST /api/orders/token
├── service/
│   ├── FcfsDbLockService.kt           # SELECT FOR UPDATE + in-tx stock decrease + order save
│   ├── FcfsRedisService.kt            # Lua script DECR + order save
│   ├── FcfsQueueService.kt            # Sorted Set + scheduler + Kafka consumer
│   └── FcfsTokenService.kt            # Redis quota + JWT issue + token-verified order
├── dto/
│   ├── FcfsOrderRequest.kt            # { productId, userId, quantity }
│   ├── FcfsOrderResponse.kt           # { orderId, status, message }
│   ├── FcfsTokenResponse.kt           # { token }
│   └── FcfsQueueStatusResponse.kt     # { status, position }
├── entity/
│   └── FcfsOrder.kt                   # Lightweight order (id, productId, userId, strategy, createdAt)
├── repository/
│   └── FcfsOrderRepository.kt         # JpaRepository<FcfsOrder, Long>
└── config/
    └── FcfsRedisConfig.kt             # Lua script bean registration
```

## Strategy Implementations

### 1. DB Lock (`POST /api/orders/db-lock`)

Request: `{ "productId": 1, "userId": 1, "quantity": 1 }`

Flow:
1. Begin transaction
2. `SELECT * FROM products WHERE id = :id FOR UPDATE` (pessimistic write lock)
3. Check `stock_quantity >= quantity`
4. If insufficient: return 409 (SOLD_OUT)
5. `product.stockQuantity -= quantity`
6. Save `FcfsOrder(productId, userId, strategy=DB_LOCK)`
7. Commit transaction
8. Return 200 with order ID

Key characteristic: Every request holds a DB connection + row lock until commit. Under high concurrency, HikariCP pool (20) becomes the bottleneck.

### 2. Redis Lua Script (`POST /api/orders/redis`)

Request: `{ "productId": 1, "userId": 1, "quantity": 1 }`

Lua script (`fcfs_stock_decrement.lua`):
```lua
local stockKey = KEYS[1]          -- fcfs:stock:{productId}
local purchasedKey = KEYS[2]      -- fcfs:purchased:{productId}
local userId = ARGV[1]
local quantity = tonumber(ARGV[2])

-- Check duplicate purchase
if redis.call('SISMEMBER', purchasedKey, userId) == 1 then
    return -1  -- ALREADY_PURCHASED
end

-- Decrement stock
local remaining = redis.call('DECRBY', stockKey, quantity)
if remaining < 0 then
    redis.call('INCRBY', stockKey, quantity)  -- rollback
    return -2  -- SOLD_OUT
end

-- Record purchase
redis.call('SADD', purchasedKey, userId)
return remaining
```

Flow:
1. Execute Lua script (atomic in Redis)
2. If result >= 0: save `FcfsOrder` to DB, return 200
3. If result == -1: return 409 (ALREADY_PURCHASED)
4. If result == -2: return 409 (SOLD_OUT)

Key characteristic: Stock check is entirely in Redis. DB connection used only for order save (1 at a time via default pool).

### 3. Queue (`POST /api/queue/enter`, `GET /api/queue/status`)

**Enter endpoint** — Request: `{ "productId": 1, "userId": 1 }`

Flow:
1. `ZADD fcfs:queue:{productId} {timestamp} {userId}` (Sorted Set)
2. Get position: `ZRANK`
3. Return 200 with `{ status: "WAITING", position: rank }`

**Status endpoint** — `GET /api/queue/status?productId=1&userId=1`

Flow:
1. Check `SISMEMBER fcfs:allowed:{productId} {userId}` → return `ALLOWED`
2. Check `ZRANK fcfs:queue:{productId} {userId}` → return `WAITING` with position
3. Otherwise → return `NOT_IN_QUEUE`

**Scheduler** (runs every 3 seconds):
1. `ZPOPMIN fcfs:queue:{productId} 10` (pop 10 users)
2. For each: `SADD fcfs:allowed:{productId} {userId}`
3. Publish order event to Kafka topic `fcfs.queue.orders`

**Kafka Consumer:**
1. Consume from `fcfs.queue.orders`
2. Check stock (atomic UPDATE like existing marketplace pattern)
3. Save `FcfsOrder` to DB
4. Remove from allowed set

**Order endpoint** — `POST /api/orders` (uses existing marketplace endpoint or a separate `POST /api/orders/queue`)

For simplicity, the Kafka consumer handles the order creation directly. The k6 script polls `/api/queue/status` until `ALLOWED`, then the consumer has already created the order.

Key characteristic: Intentionally slow (~30s for 100 users). Throughput is controlled, not maximized.

### 4. Token (`POST /api/tokens/issue`, `POST /api/orders/token`)

**Issue endpoint** — Request: `{ "productId": 1, "userId": 1 }`

Flow:
1. `DECR fcfs:quota:{productId}` in Redis
2. If result < 0: `INCR` rollback, return 409 (SOLD_OUT)
3. Check duplicate: `SISMEMBER fcfs:issued:{productId} {userId}`
4. If duplicate: `INCR` rollback, return 409 (ALREADY_ISSUED)
5. `SADD fcfs:issued:{productId} {userId}`
6. Generate JWT with claims: `{ productId, userId, exp: now+5min }`
7. Return 200 with `{ token }`

**Order endpoint** — Request: `{ "quantity": 1 }`, Header: `Authorization: Bearer {token}`

Flow:
1. Parse and validate JWT (expiry, signature)
2. Extract productId, userId from claims
3. Check used: `SISMEMBER fcfs:used:{productId} {userId}`
4. If used: return 409 (TOKEN_ALREADY_USED)
5. `SADD fcfs:used:{productId} {userId}`
6. Save `FcfsOrder` to DB
7. Return 200 with order ID

Key characteristic: Two-phase flow but each phase is fast. Token acts as a "purchase right" — separates authorization from execution.

## Entity

```kotlin
@Entity
@Table(name = "fcfs_orders")
data class FcfsOrder(
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long = 0,

    val productId: Long,
    val userId: Long,

    @Enumerated(EnumType.STRING)
    val strategy: FcfsStrategy,  // DB_LOCK, REDIS, QUEUE, TOKEN

    val createdAt: LocalDateTime = LocalDateTime.now()
)

enum class FcfsStrategy { DB_LOCK, REDIS, QUEUE, TOKEN }
```

## Stock Reset

A utility endpoint for resetting state between test runs:

`POST /api/fcfs/reset?productId=1&stock=100`

1. `UPDATE products SET stock_quantity = :stock WHERE id = :productId`
2. Delete Redis keys: `fcfs:stock:*`, `fcfs:purchased:*`, `fcfs:queue:*`, `fcfs:allowed:*`, `fcfs:quota:*`, `fcfs:issued:*`, `fcfs:used:*`
3. Delete all `FcfsOrder` rows
4. Return 200

## k6 Test Scripts

Location: `k6/` directory in project root.

### test-db-lock.js
- VUs/iterations configurable via `--vus` and `--iterations`
- Calls `POST /api/orders/db-lock`
- Custom metrics: `success_count`, `fail_count`, `purchase_time` (Trend)
- check: status is 200 or 409

### test-redis.js
- Same structure as db-lock
- Calls `POST /api/orders/redis`
- Calls reset endpoint in setup()

### test-queue.js
- Phase 1: `POST /api/queue/enter`
- Phase 2: Poll `GET /api/queue/status` every 1s (max 60 iterations)
- Phase 3: Verify order was created (status is ALLOWED means order is being processed)
- Custom metrics include total flow time (enter → order complete)

### test-token.js
- Phase 1: `POST /api/tokens/issue`
- Phase 2: `POST /api/orders/token` with Bearer token
- Custom metrics include total 2-phase time

### Common test pattern
Each script calls `POST /api/fcfs/reset?productId=1&stock=100` in `setup()` to ensure clean state.

## Test Execution Plan

```
1. docker compose up -d mysql redis kafka zookeeper
2. ./gradlew bootRun (local profile disabled — use docker profile with local overrides)
3. JVM warmup: run each test 2-3 times with 10 VUs, discard results
4. For each strategy:
   a. Reset: POST /api/fcfs/reset?productId=1&stock=100
   b. Run: k6 run --vus 100 --iterations 100 k6/test-{strategy}.js
   c. Record results
   d. Reset
   e. Run: k6 run --vus 500 --iterations 500 k6/test-{strategy}.js
   f. Record results
   g. Reset
   h. Run: k6 run --vus 1000 --iterations 1000 k6/test-{strategy}.js
   i. Record results
5. Compile results into comparison table
6. Update blog post (KO + EN) with real numbers
```

## Measurement Items

Per the blog spec:
- Total elapsed time
- Average response time
- P99 response time
- Success count
- Failure count (sold out)
- TPS (requests per second)
- DB connection max usage (via HikariCP metrics / Prometheus)
- HikariCP timeout count (for DB lock under high load)

## Blog Update Scope

After tests complete, update both files:
- `src/content/blog/fcfs-load-test-comparison.md` (Korean)
- `src/content/blog/en/fcfs-load-test-comparison.md` (English)

Replace all numeric data in sections 3-6 with actual measured values. Rewrite analysis/conclusions if data tells a different story.

## Out of Scope

- Modifying existing marketplace order flow
- Deploying to cloud / k8s for testing
- Adding Grafana dashboards (Prometheus metrics are available but manual observation is sufficient)
- CPU/memory measurements in section 5 (will use Prometheus if easy, otherwise remove from blog)
