# 선착순 4가지 방식 부하 테스트 — 설계 문서

## 목표

marketplace 프로젝트에 4가지 선착순 재고 차감 전략을 구현하고, k6로 동일 조건(재고 100개, 동시 100/500/1,000명) 부하 테스트를 실행한다. 블로그 글의 추정 수치를 실측 데이터로 교체한다.

## 제약 조건

- 모든 새 코드는 `com.example.marketplace.fcfs` 패키지에 격리 — 기존 코드 수정 없음
- 테스트 환경: MySQL/Redis/Kafka는 Docker Compose, Spring Boot 앱은 로컬 실행
- k6는 호스트 머신에서 `localhost:8080`으로 요청
- 블로그 엔드포인트와 정확히 일치: `/api/orders/db-lock`, `/api/orders/redis`, `/api/queue/enter`, `/api/tokens/issue`, `/api/orders/token`

## 패키지 구조

```
marketplace-api/src/main/kotlin/com/example/marketplace/fcfs/
├── controller/
│   ├── FcfsDbLockController.kt        # POST /api/orders/db-lock
│   ├── FcfsRedisController.kt         # POST /api/orders/redis
│   ├── FcfsQueueController.kt         # POST /api/queue/enter, GET /api/queue/status
│   └── FcfsTokenController.kt         # POST /api/tokens/issue, POST /api/orders/token
├── service/
│   ├── FcfsDbLockService.kt           # SELECT FOR UPDATE → 트랜잭션 내 재고 차감 → 주문 저장
│   ├── FcfsRedisService.kt            # Lua 스크립트로 재고 차감 → 주문 저장
│   ├── FcfsQueueService.kt            # Sorted Set + 스케줄러 + Kafka Consumer
│   └── FcfsTokenService.kt            # Redis 쿼터 + JWT 발급 + 토큰 검증 후 주문
├── dto/
│   ├── FcfsOrderRequest.kt            # { productId, userId, quantity }
│   ├── FcfsOrderResponse.kt           # { orderId, status, message }
│   ├── FcfsTokenResponse.kt           # { token }
│   └── FcfsQueueStatusResponse.kt     # { status, position }
├── entity/
│   └── FcfsOrder.kt                   # 경량 주문 엔티티 (id, productId, userId, strategy, createdAt)
├── repository/
│   └── FcfsOrderRepository.kt         # JpaRepository<FcfsOrder, Long>
└── config/
    └── FcfsRedisConfig.kt             # Lua 스크립트 빈 등록
```

## 각 방식 구현 상세

### 1. DB 락 (`POST /api/orders/db-lock`)

요청: `{ "productId": 1, "userId": 1, "quantity": 1 }`

흐름:
1. 트랜잭션 시작
2. `SELECT * FROM products WHERE id = :id FOR UPDATE` (비관적 쓰기 락)
3. `stock_quantity >= quantity` 확인
4. 재고 부족 시: 409 반환 (SOLD_OUT)
5. `product.stockQuantity -= quantity`
6. `FcfsOrder(productId, userId, strategy=DB_LOCK)` 저장
7. 트랜잭션 커밋
8. 200 + 주문 ID 반환

핵심 특성: 모든 요청이 DB 커넥션 + 행 락을 커밋까지 물고 있음. 동시성이 높아지면 HikariCP 풀(20개)이 병목.

### 2. Redis Lua 스크립트 (`POST /api/orders/redis`)

요청: `{ "productId": 1, "userId": 1, "quantity": 1 }`

Lua 스크립트 (`fcfs_stock_decrement.lua`):
```lua
local stockKey = KEYS[1]          -- fcfs:stock:{productId}
local purchasedKey = KEYS[2]      -- fcfs:purchased:{productId}
local userId = ARGV[1]
local quantity = tonumber(ARGV[2])

-- 중복 구매 확인
if redis.call('SISMEMBER', purchasedKey, userId) == 1 then
    return -1  -- ALREADY_PURCHASED
end

-- 재고 차감
local remaining = redis.call('DECRBY', stockKey, quantity)
if remaining < 0 then
    redis.call('INCRBY', stockKey, quantity)  -- 롤백
    return -2  -- SOLD_OUT
end

-- 구매 기록
redis.call('SADD', purchasedKey, userId)
return remaining
```

흐름:
1. Lua 스크립트 실행 (Redis 내에서 원자적)
2. 결과 >= 0: DB에 `FcfsOrder` 저장, 200 반환
3. 결과 == -1: 409 반환 (ALREADY_PURCHASED)
4. 결과 == -2: 409 반환 (SOLD_OUT)

핵심 특성: 재고 확인은 전부 Redis에서 처리. DB 커넥션은 주문 저장에만 사용(풀에서 1개면 충분).

### 3. 대기열 (`POST /api/queue/enter`, `GET /api/queue/status`)

**진입 엔드포인트** — 요청: `{ "productId": 1, "userId": 1 }`

흐름:
1. `ZADD fcfs:queue:{productId} {timestamp} {userId}` (Sorted Set)
2. 순번 조회: `ZRANK`
3. 200 + `{ status: "WAITING", position: rank }` 반환

**상태 조회 엔드포인트** — `GET /api/queue/status?productId=1&userId=1`

흐름:
1. `SISMEMBER fcfs:allowed:{productId} {userId}` 확인 → `ALLOWED` 반환
2. `ZRANK fcfs:queue:{productId} {userId}` 확인 → `WAITING` + 순번 반환
3. 둘 다 아니면 → `NOT_IN_QUEUE` 반환

**스케줄러** (3초마다 실행):
1. `ZPOPMIN fcfs:queue:{productId} 10` (10명씩 꺼냄)
2. 각 사용자: `SADD fcfs:allowed:{productId} {userId}`
3. Kafka 토픽 `fcfs.queue.orders`로 주문 이벤트 발행

**Kafka Consumer:**
1. `fcfs.queue.orders`에서 소비
2. 재고 확인 (기존 marketplace의 atomic UPDATE 패턴 활용)
3. DB에 `FcfsOrder` 저장
4. allowed set에서 제거

k6 스크립트는 `/api/queue/status`를 폴링하다가 `ALLOWED` 상태가 되면 완료로 판단. Consumer가 주문 생성을 직접 처리하는 구조.

핵심 특성: 의도적으로 느림 (100명 기준 ~30초). 처리량을 최대화하는 게 아니라 흐름을 제어하는 방식.

### 4. 토큰 (`POST /api/tokens/issue`, `POST /api/orders/token`)

**발급 엔드포인트** — 요청: `{ "productId": 1, "userId": 1 }`

흐름:
1. Redis에서 `DECR fcfs:quota:{productId}`
2. 결과 < 0: `INCR` 롤백, 409 반환 (SOLD_OUT)
3. 중복 확인: `SISMEMBER fcfs:issued:{productId} {userId}`
4. 중복이면: `INCR` 롤백, 409 반환 (ALREADY_ISSUED)
5. `SADD fcfs:issued:{productId} {userId}`
6. JWT 생성 (클레임: `{ productId, userId, exp: 현재시각+5분 }`)
7. 200 + `{ token }` 반환

**주문 엔드포인트** — 요청: `{ "quantity": 1 }`, 헤더: `Authorization: Bearer {token}`

흐름:
1. JWT 파싱 및 검증 (만료, 서명)
2. 클레임에서 productId, userId 추출
3. 사용 여부 확인: `SISMEMBER fcfs:used:{productId} {userId}`
4. 이미 사용됨: 409 반환 (TOKEN_ALREADY_USED)
5. `SADD fcfs:used:{productId} {userId}`
6. DB에 `FcfsOrder` 저장
7. 200 + 주문 ID 반환

핵심 특성: 2단계 호출이지만 각 단계가 빠름. 토큰이 "구매 권한" 역할 — 인가와 실행을 분리.

## 엔티티

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

## 재고 리셋

테스트 사이에 상태를 초기화하는 유틸리티 엔드포인트:

`POST /api/fcfs/reset?productId=1&stock=100`

1. `UPDATE products SET stock_quantity = :stock WHERE id = :productId`
2. Redis 키 삭제: `fcfs:stock:*`, `fcfs:purchased:*`, `fcfs:queue:*`, `fcfs:allowed:*`, `fcfs:quota:*`, `fcfs:issued:*`, `fcfs:used:*`
3. `FcfsOrder` 전체 삭제
4. 200 반환

## k6 테스트 스크립트

위치: 프로젝트 루트의 `k6/` 디렉토리

### test-db-lock.js
- VU/iterations는 `--vus`, `--iterations` 커맨드라인으로 조절 가능
- `POST /api/orders/db-lock` 호출
- 커스텀 메트릭: `success_count`, `fail_count`, `purchase_time` (Trend)
- check: 상태 코드 200 또는 409

### test-redis.js
- db-lock과 동일 구조
- `POST /api/orders/redis` 호출
- setup()에서 리셋 엔드포인트 호출

### test-queue.js
- Phase 1: `POST /api/queue/enter`
- Phase 2: `GET /api/queue/status`를 1초마다 폴링 (최대 60회)
- Phase 3: `ALLOWED` 상태 확인 → Consumer가 주문 처리 완료
- 커스텀 메트릭에 전체 흐름 시간(진입 → 주문 완료) 포함

### test-token.js
- Phase 1: `POST /api/tokens/issue`
- Phase 2: `POST /api/orders/token` (Bearer 토큰 포함)
- 커스텀 메트릭에 2단계 합산 시간 포함

### 공통 패턴
모든 스크립트는 `setup()`에서 `POST /api/fcfs/reset?productId=1&stock=100`을 호출하여 깨끗한 상태를 보장.

## 테스트 실행 순서

```
1. docker compose up -d mysql redis kafka zookeeper   (인프라만 기동)
2. ./gradlew bootRun                                   (앱 로컬 실행)
3. JVM 워밍업: 각 테스트를 10 VU로 2~3회 실행, 결과 버림
4. 각 방식별:
   a. 리셋: POST /api/fcfs/reset?productId=1&stock=100
   b. 실행: k6 run --vus 100 --iterations 100 k6/test-{방식}.js
   c. 결과 기록
   d. 리셋
   e. 실행: k6 run --vus 500 --iterations 500 k6/test-{방식}.js
   f. 결과 기록
   g. 리셋
   h. 실행: k6 run --vus 1000 --iterations 1000 k6/test-{방식}.js
   i. 결과 기록
5. 결과를 비교 표로 정리
6. 블로그 글(한국어 + 영어) 실측 데이터로 업데이트
```

## 측정 항목

블로그 스펙 기준:
- 총 소요 시간
- 평균 응답 시간
- P99 응답 시간
- 성공 건수
- 실패 건수 (품절)
- TPS (초당 처리량)
- DB 커넥션 최대 사용량 (HikariCP 메트릭 / Prometheus)
- HikariCP 타임아웃 횟수 (DB 락 고부하 시)

## 블로그 업데이트 범위

테스트 완료 후 두 파일 모두 업데이트:
- `src/content/blog/fcfs-load-test-comparison.md` (한국어)
- `src/content/blog/en/fcfs-load-test-comparison.md` (영어)

3~6절의 모든 수치를 실측 데이터로 교체. 데이터가 다른 이야기를 하면 분석/결론도 다시 작성.

## 범위 밖

- 기존 marketplace 주문 흐름 수정
- 클라우드/k8s 배포 후 테스트
- Grafana 대시보드 추가 (Prometheus 메트릭은 있지만 수동 관찰로 충분)
- 5절 CPU/메모리 측정 (Prometheus로 쉽게 되면 포함, 아니면 블로그에서 제거)
