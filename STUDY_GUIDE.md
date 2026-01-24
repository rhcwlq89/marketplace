# 대용량 시스템 학습 가이드

이 프로젝트를 통해 대용량 트래픽을 처리하는 실무급 시스템의 핵심 개념들을 학습할 수 있습니다.

---

## 목차

1. [학습 로드맵](#학습-로드맵)
2. [Phase 1: 동시성과 분산 락](#phase-1-동시성과-분산-락)
3. [Phase 2: 캐싱 전략](#phase-2-캐싱-전략)
4. [Phase 3: 메시지 큐와 이벤트 드리븐](#phase-3-메시지-큐와-이벤트-드리븐)
5. [Phase 4: 장애 대응 패턴](#phase-4-장애-대응-패턴)
6. [Phase 5: 데이터베이스 최적화](#phase-5-데이터베이스-최적화)
7. [Phase 6: 모니터링과 관측성](#phase-6-모니터링과-관측성)
8. [실습 과제](#실습-과제)
9. [면접 대비 질문](#면접-대비-질문)
10. [추천 학습 자료](#추천-학습-자료)

---

## 학습 로드맵

```
Week 1-2: 동시성 문제 이해
    └── 재고 감소 문제, Race Condition, Lost Update
    └── 낙관적 락 vs 비관적 락 vs 분산 락

Week 3-4: 캐싱
    └── 로컬 캐시 vs 분산 캐시
    └── Cache-Aside, Write-Through, Write-Behind 패턴
    └── 캐시 무효화 전략

Week 5-6: 메시지 큐
    └── 동기 vs 비동기 처리
    └── Kafka 기본 개념 (Producer, Consumer, Topic, Partition)
    └── Outbox 패턴과 Transactional Messaging

Week 7-8: 장애 대응
    └── Circuit Breaker, Retry, Timeout
    └── Rate Limiting, Bulkhead
    └── Graceful Degradation

Week 9-10: 데이터베이스
    └── 인덱스 최적화
    └── 쿼리 튜닝
    └── Read Replica, Sharding 개념

Week 11-12: 모니터링
    └── 메트릭 수집 (Prometheus)
    └── 대시보드 구성 (Grafana)
    └── 로그 분석, 분산 트레이싱
```

---

## Phase 1: 동시성과 분산 락

### 핵심 개념

#### 1.1 동시성 문제란?

```
시나리오: 상품 재고가 1개인데, 2명이 동시에 주문

[User A]                    [User B]
   │                           │
   ├─ 재고 조회: 1개            ├─ 재고 조회: 1개
   │                           │
   ├─ 재고 >= 1? YES           ├─ 재고 >= 1? YES
   │                           │
   ├─ 재고 감소: 1 → 0         ├─ 재고 감소: 1 → 0 (문제!)
   │                           │
   └─ 주문 성공                └─ 주문 성공 (재고 -1 발생!)
```

이것이 **Check-then-Act** 문제이며, **Race Condition**의 대표적인 예입니다.

#### 1.2 해결 방법 비교

| 방식 | 장점 | 단점 | 사용 시점 |
|------|------|------|----------|
| **낙관적 락** | 성능 좋음, 충돌 적을 때 효율적 | 충돌 시 재시도 필요 | 읽기 많고 충돌 적을 때 |
| **비관적 락** | 충돌 방지 확실 | DB 락으로 성능 저하 | 단일 DB, 충돌 많을 때 |
| **분산 락** | 다중 서버 환경 지원 | 추가 인프라(Redis) 필요 | MSA, 다중 인스턴스 |
| **원자적 연산** | 가장 효율적 | 복잡한 로직 어려움 | 단순 증감 연산 |

### 관련 파일

```
marketplace-api/src/main/kotlin/.../common/DistributedLock.kt     # 분산 락 AOP
marketplace-api/src/main/kotlin/.../config/RedissonConfig.kt       # Redisson 설정
marketplace-infra/src/main/kotlin/.../ProductJpaRepositoryImpl.kt  # 원자적 쿼리
marketplace-api/src/main/kotlin/.../order/OrderService.kt          # 적용 예시
```

### 코드로 이해하기

```kotlin
// 1. 분산 락 어노테이션
@DistributedLock(key = "'order:create:' + #buyerId", waitTime = 5, leaseTime = 30)
fun createOrder(buyerId: Long, req: CreateOrderRequest): OrderResponse

// 2. 원자적 재고 감소 (단일 쿼리로 Check + Update)
UPDATE Product p
SET p.stockQuantity = p.stockQuantity - :quantity
WHERE p.id = :productId
AND p.stockQuantity >= :quantity  -- 재고 충분할 때만 업데이트
```

### 실습해보기

```bash
# 1. Docker 환경 실행
docker-compose up -d

# 2. 동시 주문 테스트 (10개 동시 요청)
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/v1/orders \
    -H "Authorization: Bearer YOUR_TOKEN" \
    -H "Content-Type: application/json" \
    -d '{"orderItems":[{"productId":1,"quantity":1}],...}' &
done
wait

# 3. 재고 확인 - 음수가 되지 않아야 함
curl http://localhost:8080/api/v1/products/1
```

### 생각해볼 질문

1. 분산 락의 waitTime과 leaseTime은 어떻게 설정해야 할까?
2. 락을 획득한 상태에서 서버가 죽으면 어떻게 될까?
3. Redisson의 Watchdog은 무엇이고 왜 필요할까?

---

## Phase 2: 캐싱 전략

### 핵심 개념

#### 2.1 왜 캐시가 필요한가?

```
DB 조회: ~10ms (네트워크 + 디스크 I/O)
Redis 조회: ~1ms (네트워크 + 메모리)
로컬 캐시: ~0.01ms (메모리만)

QPS 1000일 때:
- DB만: 1000 * 10ms = 10초의 DB 부하
- 캐시 90% 히트: 100 * 10ms = 1초의 DB 부하 (10배 감소!)
```

#### 2.2 로컬 캐시 vs 분산 캐시

```
┌─────────────────────────────────────────────────────────────┐
│                     로컬 캐시 (Caffeine)                      │
├─────────────────────────────────────────────────────────────┤
│  Server A          Server B          Server C               │
│  ┌────────┐        ┌────────┐        ┌────────┐            │
│  │ Cache  │        │ Cache  │        │ Cache  │            │
│  │ Data:X │        │ Data:Y │        │ Data:Z │  ← 불일치!  │
│  └────────┘        └────────┘        └────────┘            │
└─────────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────────┐
│                     분산 캐시 (Redis)                        │
├─────────────────────────────────────────────────────────────┤
│  Server A          Server B          Server C               │
│     │                 │                 │                   │
│     └────────────────┬┴─────────────────┘                   │
│                      ▼                                      │
│              ┌──────────────┐                               │
│              │    Redis     │  ← 모든 서버가 같은 데이터    │
│              │   Cache      │                               │
│              └──────────────┘                               │
└─────────────────────────────────────────────────────────────┘
```

#### 2.3 캐싱 패턴

```kotlin
// Cache-Aside (Look-Aside) 패턴 - 가장 일반적
fun getProduct(id: Long): Product {
    // 1. 캐시 조회
    val cached = cache.get(id)
    if (cached != null) return cached

    // 2. DB 조회
    val product = repository.findById(id)

    // 3. 캐시 저장
    cache.put(id, product)

    return product
}

// Spring의 @Cacheable이 이 패턴을 자동으로 처리
@Cacheable(value = ["popularProducts"], key = "'top10'")
fun getPopularProducts(): List<ProductResponse>
```

### 관련 파일

```
marketplace-api/src/main/kotlin/.../config/CacheConfig.kt   # Caffeine (local)
marketplace-api/src/main/kotlin/.../config/RedisConfig.kt   # Redis (docker/prod)
marketplace-api/src/main/kotlin/.../product/ProductService.kt  # @Cacheable 사용
```

### 실습해보기

```bash
# 1. 캐시 히트 확인 (응답 시간 비교)
time curl http://localhost:8080/api/v1/products/popular  # 첫 호출 (캐시 미스)
time curl http://localhost:8080/api/v1/products/popular  # 두 번째 (캐시 히트)

# 2. Redis에서 캐시 확인 (Docker 환경)
docker exec -it marketplace-redis redis-cli
> KEYS *
> GET popularProducts::top10

# 3. 캐시 무효화 테스트 - 상품 수정 후 캐시 확인
curl -X PATCH http://localhost:8080/api/v1/products/1 \
  -H "Authorization: Bearer SELLER_TOKEN" \
  -d '{"name":"수정된 상품"}'
```

### 생각해볼 질문

1. 캐시 TTL은 어떻게 설정해야 할까? 너무 짧으면? 너무 길면?
2. 캐시 무효화는 언제 해야 할까? (Write-through vs Cache invalidation)
3. 캐시 스탬피드(Thundering Herd)는 무엇이고 어떻게 방지할까?

---

## Phase 3: 메시지 큐와 이벤트 드리븐

### 핵심 개념

#### 3.1 동기 vs 비동기

```
동기 처리 (문제점):
┌──────┐    ┌──────┐    ┌──────┐    ┌──────┐
│ 주문 │───▶│ 결제 │───▶│ 알림 │───▶│ 응답 │
└──────┘    └──────┘    └──────┘    └──────┘
   │           │           │           │
   └───────────┴───────────┴───────────┘
              총 3초 (사용자 대기)

              알림 서비스 장애 시 → 주문도 실패!

비동기 처리 (Kafka):
┌──────┐    ┌──────┐              ┌──────┐
│ 주문 │───▶│ 응답 │              │ 알림 │
└──────┘    └──────┘              └──────┘
   │           │                     ▲
   │    0.5초  │                     │
   │           │     ┌───────────────┘
   └───────────┼────▶│ Kafka (비동기)
               │     │
               │     └───▶ 알림 서비스가 나중에 처리
```

#### 3.2 Kafka 기본 구조

```
Producer ──▶ Topic ──▶ Consumer

Topic (orders.created):
┌─────────────────────────────────────────────┐
│  Partition 0: [msg1] [msg2] [msg5] [msg8]   │
│  Partition 1: [msg3] [msg4] [msg6] [msg7]   │
│  Partition 2: [msg9] [msg10]                │
└─────────────────────────────────────────────┘
                    │
      ┌─────────────┼─────────────┐
      ▼             ▼             ▼
 Consumer 1    Consumer 2    Consumer 3
 (Group A)     (Group A)     (Group A)

- 같은 Consumer Group의 Consumer들은 파티션을 나눠 처리
- 순서 보장은 파티션 내에서만
```

#### 3.3 Outbox 패턴

```
문제: DB 저장은 성공했는데 Kafka 발행이 실패하면?

해결: Outbox 테이블을 사용한 트랜잭션 보장

┌─────────────────────────────────────────────────────┐
│              같은 DB 트랜잭션 내에서                  │
│  ┌─────────┐      ┌──────────────────┐              │
│  │ Orders  │      │ Outbox Events    │              │
│  │ INSERT  │  +   │ INSERT           │  = 원자적    │
│  └─────────┘      └──────────────────┘              │
└─────────────────────────────────────────────────────┘
                           │
                           ▼
              ┌────────────────────────┐
              │ Scheduler (1초마다)     │
              │ Outbox → Kafka 발행    │
              │ 성공 시 상태 변경       │
              └────────────────────────┘
```

### 관련 파일

```
marketplace-api/src/main/kotlin/.../config/KafkaConfig.kt       # Kafka 설정
marketplace-domain/src/main/kotlin/.../outbox/OutboxEvent.kt    # Outbox 엔티티
marketplace-api/src/main/kotlin/.../outbox/OutboxPublisher.kt   # 발행 스케줄러
```

### 실습해보기

```bash
# 1. Kafka 토픽 확인
docker exec -it marketplace-kafka kafka-topics \
  --bootstrap-server localhost:9092 --list

# 2. 토픽의 메시지 확인
docker exec -it marketplace-kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic marketplace.order.created \
  --from-beginning

# 3. 주문 생성 후 Kafka 메시지 확인
# (다른 터미널에서 주문 생성)
```

### 생각해볼 질문

1. Kafka의 at-least-once, at-most-once, exactly-once 차이는?
2. Consumer가 처리 중 죽으면 메시지는 어떻게 되는가?
3. Partition 수는 어떻게 결정해야 할까?

---

## Phase 4: 장애 대응 패턴

### 핵심 개념

#### 4.1 Circuit Breaker

```
상태 전이:
        ┌─────────────────────────────────────┐
        │                                     │
        ▼                                     │
    ┌────────┐   실패율 > 임계값   ┌────────┐  │
    │ CLOSED │ ─────────────────▶ │  OPEN  │  │
    └────────┘                    └────────┘  │
        ▲                             │       │
        │                      대기 시간 후    │
        │                             │       │
        │    ┌─────────────┐          ▼       │
        │    │ HALF_OPEN   │◀─────────┘       │
        │    └─────────────┘                  │
        │          │                          │
        │    일부 요청 성공                    │
        └──────────┴──────────────────────────┘

CLOSED: 정상 상태, 모든 요청 통과
OPEN: 장애 상태, 요청 즉시 실패 (빠른 실패)
HALF_OPEN: 복구 확인, 일부 요청만 통과
```

```kotlin
@CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
fun createOrder(...): OrderResponse

fun createOrderFallback(ex: Throwable): OrderResponse {
    throw BusinessException(ErrorCode.SERVICE_UNAVAILABLE)
}
```

#### 4.2 Rate Limiting

```
목적: API 남용 방지, 시스템 보호

예: 주문 생성 - 초당 10개 제한

요청 1-10: ✅ 정상 처리
요청 11:   ❌ 429 Too Many Requests

구현:
- Token Bucket: 일정 속도로 토큰 생성, 요청마다 토큰 소비
- Sliding Window: 시간 윈도우 내 요청 수 카운트
```

#### 4.3 Bulkhead (격벽)

```
문제: 하나의 느린 API가 전체 스레드를 점유

┌────────────────────────────────────────────────┐
│ Thread Pool (10개)                              │
│  ┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐┌───┐│
│  │ A ││ A ││ A ││ A ││ A ││ A ││ A ││ A ││ A ││ A ││
│  └───┘└───┘└───┘└───┘└───┘└───┘└───┘└───┘└───┘└───┘│
└────────────────────────────────────────────────┘
  ↑ 느린 API A가 모든 스레드 점유 → API B 요청 불가!

해결: Bulkhead로 리소스 격리

┌─────────────────────┐  ┌─────────────────────┐
│ API A Pool (5개)    │  │ API B Pool (5개)    │
│ ┌───┐┌───┐┌───┐┌───┐┌───┐│  │┌───┐┌───┐┌───┐┌───┐┌───┐│
│ │ A ││ A ││ A ││ A ││ A ││  ││ B ││ B ││ - ││ - ││ - ││
│ └───┘└───┘└───┘└───┘└───┘│  │└───┘└───┘└───┘└───┘└───┘│
└─────────────────────┘  └─────────────────────┘
  ↑ A가 느려도 B는 정상 동작!
```

### 관련 파일

```
marketplace-api/src/main/kotlin/.../config/RateLimitingFilter.kt  # Rate Limiter
marketplace-api/src/main/resources/application.yml                # Resilience4j 설정
marketplace-api/src/main/kotlin/.../order/OrderService.kt         # @CircuitBreaker
```

### 실습해보기

```bash
# 1. Rate Limiting 테스트 (빠르게 여러 번 호출)
for i in {1..20}; do
  curl -w "%{http_code}\n" -o /dev/null -s \
    -X POST http://localhost:8080/api/v1/orders \
    -H "Authorization: Bearer TOKEN" \
    -H "Content-Type: application/json" \
    -d '...'
done
# 429 응답 확인

# 2. Circuit Breaker 상태 확인
curl http://localhost:8080/actuator/health | jq '.components.circuitBreakers'
```

### 생각해볼 질문

1. Circuit Breaker의 실패율 임계값은 어떻게 설정해야 할까?
2. fallback에서는 어떤 처리를 해야 할까?
3. Retry와 Circuit Breaker를 함께 사용할 때 주의점은?

---

## Phase 5: 데이터베이스 최적화

### 핵심 개념

#### 5.1 인덱스

```sql
-- 인덱스 없이: Full Table Scan (O(n))
SELECT * FROM products WHERE status = 'ON_SALE';
-- 100만 건 → 100만 행 스캔

-- 인덱스 있으면: Index Scan (O(log n))
CREATE INDEX idx_products_status ON products(status);
-- 100만 건 → ~20번 비교로 찾음
```

**복합 인덱스 순서가 중요!**

```sql
-- 인덱스: (status, created_at)

-- ✅ 인덱스 사용됨
WHERE status = 'ON_SALE' AND created_at > '2024-01-01'
WHERE status = 'ON_SALE'

-- ❌ 인덱스 사용 안됨 (선행 컬럼 조건 없음)
WHERE created_at > '2024-01-01'
```

#### 5.2 커서 기반 페이지네이션

```
OFFSET 방식 (문제):
Page 1: SELECT ... LIMIT 20 OFFSET 0     -- 빠름
Page 100: SELECT ... LIMIT 20 OFFSET 1980  -- 1980개 스킵 → 느림!
Page 1000: SELECT ... OFFSET 19980         -- 매우 느림!

커서 방식 (해결):
SELECT * FROM products
WHERE (created_at, id) < (:cursor_time, :cursor_id)
ORDER BY created_at DESC, id DESC
LIMIT 20;
-- 항상 인덱스 사용, 일정한 성능
```

#### 5.3 Read Replica

```
┌─────────────────────────────────────────────────────┐
│                    Application                       │
│                         │                           │
│         ┌───────────────┴───────────────┐           │
│         ▼                               ▼           │
│   ┌──────────┐                   ┌──────────┐       │
│   │  Primary │ ─── 복제 ───────▶ │ Replica  │       │
│   │  (Write) │                   │  (Read)  │       │
│   └──────────┘                   └──────────┘       │
│         ▲                               ▲           │
│   INSERT/UPDATE                   SELECT only       │
│   DELETE                                            │
└─────────────────────────────────────────────────────┘

@Transactional(readOnly = true)  // → Replica로 라우팅
@Transactional                   // → Primary로 라우팅
```

### 관련 파일

```
marketplace-api/src/main/resources/db/migration/V2__add_indexes.sql  # 인덱스
marketplace-api/src/main/kotlin/.../common/CursorPageResponse.kt     # 커서 응답
marketplace-infra/src/main/kotlin/.../ProductJpaRepositoryImpl.kt    # 커서 쿼리
marketplace-api/src/main/kotlin/.../config/DataSourceConfig.kt       # Read Replica
```

### 실습해보기

```bash
# 1. 쿼리 실행 계획 확인 (H2 Console 또는 MySQL)
EXPLAIN SELECT * FROM products WHERE status = 'ON_SALE';

# 2. 커서 페이지네이션 테스트
curl "http://localhost:8080/api/v1/products/cursor?limit=5"
# nextCursor 값으로 다음 페이지
curl "http://localhost:8080/api/v1/products/cursor?limit=5&cursor=NEXT_CURSOR"
```

### 생각해볼 질문

1. 인덱스가 많으면 좋은 걸까? 단점은?
2. N+1 문제란 무엇이고 어떻게 해결할까?
3. Read Replica의 복제 지연(Replication Lag)은 어떻게 처리해야 할까?

---

## Phase 6: 모니터링과 관측성

### 핵심 개념

#### 6.1 세 가지 기둥

```
┌─────────────────────────────────────────────────────┐
│                   Observability                      │
├─────────────────┬─────────────────┬─────────────────┤
│     Metrics     │      Logs       │     Traces      │
│  (숫자 데이터)   │  (이벤트 기록)   │  (요청 추적)    │
├─────────────────┼─────────────────┼─────────────────┤
│ - CPU 사용률    │ - 에러 로그      │ - 요청 경로     │
│ - 요청 수       │ - 접근 로그      │ - 서비스 간 호출 │
│ - 응답 시간     │ - 디버그 정보    │ - 지연 시간     │
├─────────────────┼─────────────────┼─────────────────┤
│   Prometheus    │   ELK Stack     │     Jaeger      │
│   + Grafana     │   Loki          │     Zipkin      │
└─────────────────┴─────────────────┴─────────────────┘
```

#### 6.2 주요 메트릭 (RED Method)

```
Rate    - 초당 요청 수 (QPS/RPS)
Errors  - 에러 발생 비율
Duration - 응답 시간 (p50, p95, p99)

예시 대시보드 쿼리:
- QPS: rate(http_server_requests_seconds_count[1m])
- 에러율: rate(http_server_requests_seconds_count{status="5xx"}[1m])
- p95 응답시간: histogram_quantile(0.95, rate(http_server_requests_seconds_bucket[5m]))
```

#### 6.3 커스텀 비즈니스 메트릭

```kotlin
// 주문 성공/실패 카운터
orderCreatedCounter.increment()
orderFailedCounter.increment()

// 주문 처리 시간
orderCreationTimer.record(duration)

// 활성 주문 수 (게이지)
meterRegistry.gauge("marketplace.orders.active", activeOrders)
```

### 관련 파일

```
marketplace-api/src/main/kotlin/.../config/MetricsConfig.kt      # 비즈니스 메트릭
marketplace-api/src/main/kotlin/.../config/HealthIndicators.kt   # 헬스체크
prometheus/prometheus.yml                                        # Prometheus 설정
grafana/provisioning/dashboards/marketplace-dashboard.json       # 대시보드
```

### 실습해보기

```bash
# 1. Prometheus 메트릭 확인
curl http://localhost:8080/actuator/prometheus | grep marketplace

# 2. Grafana 대시보드 접속
open http://localhost:3000  # admin / admin123

# 3. 헬스체크 상세 정보
curl http://localhost:8080/actuator/health | jq
```

### 생각해볼 질문

1. p99와 평균 응답시간 중 어떤 것이 더 중요할까?
2. 알람은 어떤 기준으로 설정해야 할까?
3. 로그 레벨은 어떻게 관리해야 할까?

---

## 실습 과제

### 초급

1. **캐시 TTL 변경하기**
   - `RedisConfig.kt`에서 popularProducts 캐시 TTL을 5분으로 변경
   - 변경 후 캐시 동작 확인

2. **새로운 메트릭 추가하기**
   - 회원가입 성공/실패 카운터 추가
   - Prometheus에서 메트릭 확인

3. **인덱스 효과 확인하기**
   - 인덱스 삭제 전/후 쿼리 실행 시간 비교
   - EXPLAIN으로 실행 계획 분석

### 중급

4. **분산 락 테스트하기**
   - 동시에 100개 주문 요청 보내기
   - 재고가 정확하게 관리되는지 확인
   - 락 획득 실패 시 에러 응답 확인

5. **Circuit Breaker 동작 테스트**
   - 의도적으로 오류 발생시키기
   - OPEN 상태로 전환 확인
   - HALF_OPEN, CLOSED 상태 복구 확인

6. **Kafka Consumer 구현하기**
   - `OrderCreatedEvent`를 consume하는 리스너 작성
   - 이메일 발송 로직 (로그로 대체) 구현

### 고급

7. **캐시 무효화 전략 개선**
   - 상품 수정 시 관련 캐시 모두 무효화
   - 캐시 스탬피드 방지 로직 추가

8. **Read Replica 라우팅 구현**
   - 실제 MySQL Primary/Replica 구성
   - 쿼리별 라우팅 확인

9. **분산 트레이싱 추가**
   - Zipkin 또는 Jaeger 연동
   - 요청 경로 추적 확인

---

## 면접 대비 질문

### 동시성

1. 동시에 같은 상품에 100명이 주문하면 어떻게 처리하나요?
2. 분산 락과 DB 락의 차이점은 무엇인가요?
3. Redisson의 Watchdog 메커니즘을 설명해주세요.

### 캐싱

4. 캐시 무효화 전략에는 어떤 것들이 있나요?
5. 캐시 스탬피드(Thundering Herd)를 어떻게 방지하나요?
6. 로컬 캐시와 분산 캐시를 언제 사용하나요?

### 메시지 큐

7. Kafka의 파티션은 어떤 역할을 하나요?
8. exactly-once 전달을 어떻게 보장하나요?
9. Outbox 패턴은 왜 필요하고 어떻게 동작하나요?

### 장애 대응

10. Circuit Breaker의 상태 전이를 설명해주세요.
11. Rate Limiting은 어떤 방식으로 구현할 수 있나요?
12. Graceful Shutdown은 왜 필요한가요?

### 데이터베이스

13. 인덱스의 장단점은 무엇인가요?
14. 커서 기반 페이지네이션의 장점은 무엇인가요?
15. N+1 문제를 어떻게 해결하나요?

### 모니터링

16. p99 응답시간이 중요한 이유는 무엇인가요?
17. 어떤 메트릭을 모니터링해야 하나요?
18. 알람 설정 시 주의할 점은 무엇인가요?

---

## 추천 학습 자료

### 책

- **가상 면접 사례로 배우는 대규모 시스템 설계 기초** (알렉스 쉬)
- **데이터 중심 애플리케이션 설계** (마틴 클레프만)
- **Release It! 배포 가능한 소프트웨어 만들기** (마이클 나이가드)

### 온라인 자료

- [Redis Documentation](https://redis.io/docs/)
- [Kafka Documentation](https://kafka.apache.org/documentation/)
- [Resilience4j Documentation](https://resilience4j.readme.io/)
- [Spring Boot Actuator Guide](https://docs.spring.io/spring-boot/docs/current/reference/html/actuator.html)

### 유튜브/강의

- 우아한테크세미나 - 대용량 트래픽 관련 발표
- Martin Fowler의 Microservices 관련 발표
- InfoQ의 시스템 설계 관련 발표

---

## 마무리

이 프로젝트를 통해 학습한 내용을 실제 업무에 적용할 때 기억할 점:

1. **모든 기술에는 트레이드오프가 있다** - 복잡성 vs 필요성을 항상 고려
2. **측정 없이 최적화 없다** - 병목 지점을 먼저 파악하고 개선
3. **장애는 반드시 발생한다** - 장애 상황을 가정하고 설계
4. **점진적으로 개선하라** - 한 번에 모든 것을 바꾸려 하지 말 것

Happy Learning! 🚀
