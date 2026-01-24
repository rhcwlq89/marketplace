# 재고 동시성 제어 및 Resilience 패턴 상세 가이드

> **이 문서의 대상**: 마켓플레이스 시스템의 동시성 제어와 장애 대응 패턴을 이해하고자 하는 백엔드 개발자

---

## TL;DR (핵심 요약)

```
재고 과잉 판매 방지 = 원자적 UPDATE (DB 조건부 갱신)
중복 주문 방지 = 멱등성 키 또는 DB 유니크 제약 (분산 락은 오버엔지니어링)
장애 대응 = Circuit Breaker + Bulkhead + Retry
```

| 문제 | 해결책 | 설명 |
|-----|--------|------|
| **재고 과잉 판매** | **Atomic Update** | `UPDATE WHERE stock >= qty` 조건부 감소 |
| **쿠폰 중복 사용** | **Atomic Update** | `UPDATE WHERE used = false` 조건부 갱신 |
| **중복 주문 (따닥)** | **멱등성 키** | 클라이언트 UUID + Redis 캐시 (권장) |
| **장애 전파** | **Circuit Breaker** | 실패율 50% 초과 시 요청 차단 |
| **리소스 고갈** | **Bulkhead** | 동시 요청 20개로 제한 |

> **분산 락이 필요한 경우**: 캐시 스탬피드, 배치 중복 실행, 외부 API 제약, 장시간 리소스 선점

---

## 목차

### Part 1: 동시성 제어
1. [문제 정의: 왜 동시성 제어가 필요한가?](#1-문제-정의-왜-동시성-제어가-필요한가)
2. [해결책 1: 원자적 재고 업데이트 (Overselling 방지)](#2-해결책-1-원자적-재고-업데이트-overselling-방지)
3. [중복 주문 방지: 분산 락 vs 대안](#3-중복-주문-방지-분산-락-vs-대안)
   - 3.1 [핵심 질문: 분산 락이 정말 필요한가?](#31-핵심-질문-분산-락이-정말-필요한가)
   - 3.2 [권장 해결책: 멱등성 키](#32-권장-해결책-멱등성-키-idempotency-key)
   - 3.3 [분산 락이 진짜 필요한 경우](#33-분산-락이-진짜-필요한-경우)
4. [분산 락 심화](#4-분산-락-심화)
   - 4.1 [Redis 동작 원리](#41-redis에서의-분산-락-동작-원리)
   - 4.2 [구현 방법 비교 (Redisson vs 대안)](#42-분산-락-구현-방법-비교)
   - 4.3 [락 전략 선택 가이드 (낙관적 vs 비관적 vs 분산)](#43-락-전략-선택-가이드)

### Part 2: Resilience 패턴
5. [Circuit Breaker (서킷 브레이커)](#5-circuit-breaker-서킷-브레이커)
6. [Bulkhead (벌크헤드)](#6-bulkhead-벌크헤드)
7. [Retry (재시도)](#7-retry-재시도)

### Part 3: 통합 및 운영
8. [전체 동작 흐름](#8-전체-동작-흐름)
9. [설정 값 가이드](#9-설정-값-가이드)
10. [테스트 방법](#10-테스트-방법)
11. [운영 환경 문제 해결](#11-운영-환경-문제-해결-가이드)
12. [FAQ (자주 묻는 질문)](#faq-자주-묻는-질문)

---

# Part 1: 동시성 제어

## 1. 문제 정의: 왜 동시성 제어가 필요한가?

이커머스에서 발생하는 두 가지 핵심 동시성 문제와 각각의 해결책을 알아봅니다.

### 1.1 문제 1: 재고 과잉 판매 (Overselling)

재고가 1개 남은 상품에 2명이 동시에 주문하는 상황:

```
시간    사용자 A              사용자 B              재고(DB)
─────────────────────────────────────────────────────────────
T1      재고 조회 → 1개        -                    1
T2      -                    재고 조회 → 1개        1
T3      1 >= 1 → 주문 가능!   -                    1
T4      -                    1 >= 1 → 주문 가능!   1
T5      재고 감소 (1→0)       -                    0
T6      -                    재고 감소 (0→-1) ⚠️   -1 ❌
```

**결과**: 재고 1개인데 2개 판매 → **과잉 판매(Overselling)** 발생!

**원인**: Check-Then-Act 패턴의 취약점

```kotlin
// ❌ 위험한 코드
fun createOrder(productId: Long, quantity: Int) {
    val product = productRepository.findById(productId)
    if (product.stockQuantity >= quantity) {       // Check
        product.stockQuantity -= quantity          // Act (이 사이에 끼어듦!)
        productRepository.save(product)
    }
}
```

> **해결책**: **원자적 UPDATE** (섹션 2에서 설명)

---

### 1.2 문제 2: 중복 주문 / 쿠폰 중복 사용

같은 사용자가 주문 버튼을 연타하거나, 쿠폰을 중복 사용하려는 상황:

```
시간    사용자 A (요청 1)         사용자 A (요청 2)         문제
─────────────────────────────────────────────────────────────────
T1      쿠폰 조회 → 있음           -
T2      -                        쿠폰 조회 → 있음
T3      쿠폰 사용 처리             -
T4      -                        쿠폰 사용 처리             ⚠️ 중복 사용?
T5      주문 생성 #1              -
T6      -                        주문 생성 #2              ⚠️ 중복 주문?
```

**해결책**:
- **쿠폰 중복 사용** → 원자적 UPDATE로 해결 가능 (`UPDATE WHERE used = false`)
- **중복 주문** → 멱등성 키 또는 DB 유니크 제약으로 해결

> **참고**: 분산 락 없이도 대부분의 경우 해결됩니다. (섹션 3에서 자세히 설명)

---

### 1.3 문제별 해결책 요약

| 문제 | 원인 | 권장 해결책 | 비고 |
|------|------|------------|------|
| **재고 과잉 판매** | Check-Then-Act | **원자적 UPDATE** | 필수 |
| **쿠폰 중복 사용** | Check-Then-Act | **원자적 UPDATE** | 필수 |
| **중복 주문 (따닥)** | 버튼 연타 | **멱등성 키** | 권장 |
| **캐시 스탬피드** | 캐시 만료 | **분산 락** | 선택 |
| **배치 중복 실행** | 다중 인스턴스 | **분산 락** | 선택 |

```
┌─────────────────────────────────────────────────────────┐
│                  일반적인 주문 처리 구조                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  요청 → [멱등성 체크] → [원자적 UPDATE] → 주문 생성 → 완료  │
│              │                  │                       │
│         중복 요청 방지      재고/쿠폰 보호                 │
│                                                         │
│  ※ 분산 락은 특수한 경우에만 필요 (섹션 3.3 참고)          │
└─────────────────────────────────────────────────────────┘
```

---

## 2. 해결책 1: 원자적 재고 업데이트 (Overselling 방지)

### 2.1 원자적(Atomic) 연산이란?

중간에 끊기지 않고 **한 번에 완료**되는 연산. 다른 트랜잭션이 끼어들 수 없음.

```
┌─────────────────────────────────────────────────────────────┐
│  일반 방식 (3단계)                 원자적 방식 (1단계)         │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. SELECT stock     ← 끼어들 수 있음                        │
│  2. 애플리케이션 계산  ← 끼어들 수 있음      vs    1. UPDATE   │
│  3. UPDATE stock     ← 끼어들 수 있음            WHERE 조건  │
│                                                             │
│  ❌ Race Condition 발생              ✅ DB가 원자성 보장      │
└─────────────────────────────────────────────────────────────┘
```

**핵심**: 조건 확인과 업데이트를 **하나의 SQL문**으로 처리하면, DB 엔진이 원자성을 보장합니다.

### 2.2 비교: 기존 방식 vs 원자적 방식

```kotlin
// ❌ 기존 방식 (3번의 쿼리, 중간에 끼어들 수 있음)
val product = repository.findById(id)        // SELECT
if (product.stockQuantity >= quantity) {
    product.stockQuantity -= quantity
    repository.save(product)                  // UPDATE
}

// ✅ 원자적 방식 (1번의 쿼리로 조건 확인 + 업데이트)
val updated = repository.decreaseStockAtomically(id, quantity)
if (updated == 0) throw BusinessException(ErrorCode.INSUFFICIENT_STOCK)
```

### 2.3 원자적 재고 감소 쿼리

```kotlin
@Modifying
@Query("""
    UPDATE Product p
    SET p.stockQuantity = p.stockQuantity - :quantity,
        p.salesCount = p.salesCount + :quantity
    WHERE p.id = :productId
    AND p.stockQuantity >= :quantity   -- ⭐ 핵심: 조건부 업데이트
    AND p.status = 'ON_SALE'
""")
fun decreaseStockAtomically(productId: Long, quantity: Int): Int
```

### 2.4 동시 요청 시 동작

```
시간    사용자 A                           사용자 B
────────────────────────────────────────────────────────────
        재고: 1개

T1      UPDATE WHERE stock >= 1           UPDATE WHERE stock >= 1
        ↓                                 ↓
        DB Row Lock 획득                   DB Row Lock 대기...

T2      stock = 0으로 변경                 (대기중)
        COMMIT

T3      updateCount = 1 ✅                 DB Row Lock 획득
                                          stock(0) >= 1? → FALSE

T4                                        updateCount = 0 ❌
                                          → INSUFFICIENT_STOCK
```

**결과**: 정확히 1개만 판매됨!

#### 왜 동작하는가? (DB Row Lock)

```
┌─────────────────────────────────────────────────────────────┐
│  InnoDB (MySQL) / PostgreSQL의 Row-Level Lock               │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  UPDATE 문 실행 시:                                          │
│  1. 해당 Row에 Exclusive Lock (X-Lock) 획득                  │
│  2. 다른 트랜잭션은 같은 Row 수정 불가 (대기)                   │
│  3. COMMIT 후 Lock 해제 → 다음 트랜잭션 진행                  │
│                                                             │
│  ※ WHERE 조건은 Lock 획득 후 재평가됨                        │
│  → 이미 재고가 0이면 조건 불충족 → updateCount = 0           │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

> **핵심**: DB 자체의 Row Lock 메커니즘이 동시성을 처리합니다. 별도의 분산 락 없이도 재고 보호가 가능한 이유입니다.

---

## 3. 중복 주문 방지: 분산 락 vs 대안

### 3.1 핵심 질문: 분산 락이 정말 필요한가?

일반적인 주문 생성에서 분산 락은 **오버엔지니어링**일 수 있습니다.

| 문제 | 분산 락 필요? | 더 나은 대안 |
|------|:------------:|-------------|
| 재고 과잉 판매 | ❌ | 원자적 UPDATE |
| 쿠폰 중복 사용 | ❌ | 원자적 UPDATE |
| 중복 주문 (따닥) | ❌ | 멱등성 키, DB 유니크 |
| 캐시 스탬피드 | ✅ | - |
| 배치 중복 실행 | ✅ | - |
| 외부 API 직렬화 제약 | ✅ | - |

### 3.2 권장 해결책: 멱등성 키 (Idempotency Key)

```kotlin
@PostMapping("/orders")
fun createOrder(
    @RequestHeader("Idempotency-Key") idempotencyKey: String,
    @RequestBody request: OrderCreateRequest
): OrderResponse {
    // 1. 이미 처리된 요청인지 확인
    val cached = redisTemplate.opsForValue().get("idempotency:$idempotencyKey")
    if (cached != null) return cached  // 이전 결과 반환

    // 2. 새로운 주문 처리
    val result = orderService.createOrder(request)

    // 3. 결과 캐시 (24시간)
    redisTemplate.opsForValue().set("idempotency:$idempotencyKey", result, 24, TimeUnit.HOURS)
    return result
}
```

**클라이언트 측:**
```javascript
const response = await fetch('/api/v1/orders', {
    method: 'POST',
    headers: {
        'Idempotency-Key': crypto.randomUUID(),  // 요청마다 고유 키
        'Content-Type': 'application/json'
    },
    body: JSON.stringify(orderData)
});
```

#### 멱등성 키 구현 시 주의사항

| 상황 | 문제 | 해결 |
|------|------|------|
| 키 없이 요청 | 중복 방지 안 됨 | 서버에서 키 필수 검증 또는 자동 생성 |
| 동일 키로 다른 요청 | 잘못된 결과 반환 | 키 + 요청 해시 함께 저장 |
| 처리 중 재요청 | 중복 실행 가능 | 처리 중 상태 별도 관리 |
| 캐시 만료 후 재요청 | 중복 주문 발생 | TTL을 비즈니스 요구에 맞게 설정 |

```kotlin
// 개선된 구현 (처리 중 상태 관리)
fun createOrder(idempotencyKey: String, request: OrderCreateRequest): OrderResponse {
    val cacheKey = "idempotency:$idempotencyKey"

    // 1. 이미 완료된 요청 확인
    val cached = redisTemplate.opsForValue().get(cacheKey)
    if (cached is OrderResponse) return cached

    // 2. 처리 중인지 확인 (SETNX로 원자적 체크)
    val acquired = redisTemplate.opsForValue()
        .setIfAbsent("$cacheKey:processing", "1", Duration.ofSeconds(30))
    if (acquired != true) {
        throw BusinessException(ErrorCode.REQUEST_IN_PROGRESS)  // 잠시 후 재시도 요청
    }

    try {
        // 3. 주문 처리
        val result = orderService.createOrder(request)

        // 4. 결과 캐시
        redisTemplate.opsForValue().set(cacheKey, result, Duration.ofHours(24))
        return result
    } finally {
        redisTemplate.delete("$cacheKey:processing")
    }
}
```

### 3.3 분산 락이 진짜 필요한 경우

#### 1. 캐시 스탬피드 (Thundering Herd) 방지

```kotlin
fun getProduct(productId: Long): Product {
    val cached = redisTemplate.opsForValue().get("product:$productId")
    if (cached != null) return cached

    // 캐시 미스 시 1000개 요청이 동시에 DB 조회 → DB 죽음
    val lock = redissonClient.getLock("cache:product:$productId")

    return if (lock.tryLock(1, 5, TimeUnit.SECONDS)) {
        try {
            // Double-check
            val recheck = redisTemplate.opsForValue().get("product:$productId")
            if (recheck != null) return recheck

            // 1개만 DB 조회
            val product = productRepository.findById(productId)
            redisTemplate.opsForValue().set("product:$productId", product, 1, TimeUnit.HOURS)
            product
        } finally {
            lock.unlock()
        }
    } else {
        Thread.sleep(100)
        redisTemplate.opsForValue().get("product:$productId")!!
    }
}
```

#### 2. 다중 인스턴스 스케줄러 (배치 중복 실행 방지)

```kotlin
@Scheduled(cron = "0 0 0 * * *")
fun dailySettlement() {
    val lock = redissonClient.getLock("batch:daily-settlement")

    if (lock.tryLock(0, 30, TimeUnit.MINUTES)) {
        try {
            settlementService.process()  // 30분 소요
        } finally {
            lock.unlock()
        }
    }
    // 락 못 잡으면 다른 인스턴스가 실행 중 → 무시
}
```

#### 3. 외부 API 직렬화 제약

```kotlin
// PG사가 동일 사용자 동시 결제 요청 시 오류 발생
@DistributedLock(key = "'payment:' + #userId")
fun processPayment(userId: Long, amount: Long) {
    paymentGateway.charge(userId, amount)  // 외부 API
}
```

#### 4. 장시간 리소스 선점 (좌석 예약)

```kotlin
fun reserveSeat(seatId: Long, userId: Long): Reservation {
    val lock = redissonClient.getLock("seat:$seatId")

    if (lock.tryLock(0, 10, TimeUnit.MINUTES)) {  // 10분간 선점
        return Reservation(seatId, userId, expireAt = now() + 10.minutes)
    } else {
        throw BusinessException("이미 다른 사용자가 선택 중입니다")
    }
}
```

### 3.4 해결책 비교

| 방법 | 구현 복잡도 | 인프라 | 적합한 상황 |
|------|:----------:|:------:|------------|
| **멱등성 키** | 낮음 | Redis | 중복 주문 방지 (권장) |
| **DB 유니크 제약** | 매우 낮음 | 없음 | 단순한 중복 방지 |
| **프론트엔드 디바운싱** | 매우 낮음 | 없음 | 1차 방어선 |
| **분산 락** | 중간 | Redis | 캐시/배치/외부API |

### 3.5 이 프로젝트의 분산 락

> **참고**: 이 프로젝트에서 주문 생성에 분산 락을 적용한 것은 **학습/데모 목적**입니다.
> 실무에서는 멱등성 키로 충분한 경우가 많습니다.

```kotlin
// 현재 구현 (학습 목적)
@DistributedLock(key = "'order:create:' + #buyerId")
fun createOrder(buyerId: Long, request: OrderCreateRequest): OrderResponse {
    // ...
}

// 실무 권장 (더 가벼움)
fun createOrder(idempotencyKey: String, request: OrderCreateRequest): OrderResponse {
    val cached = redis.get("idempotency:$idempotencyKey")
    if (cached != null) return cached
    // ...
}
```

---

## 4. 분산 락 심화

### 4.1 Redis에서의 분산 락 동작 원리

#### Redisson의 Lua 스크립트 (락 획득)

```lua
-- 락이 없으면 새로 생성
if redis.call('exists', KEYS[1]) == 0 then
    redis.call('hset', KEYS[1], ARGV[2], 1)      -- 소유자 ID 저장
    redis.call('pexpire', KEYS[1], ARGV[1])      -- TTL 설정
    return nil  -- 락 획득 성공
end

-- 같은 스레드가 이미 보유 중이면 (재진입)
if redis.call('hexists', KEYS[1], ARGV[2]) == 1 then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)   -- 카운트 증가
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil  -- 락 획득 성공
end

return redis.call('pttl', KEYS[1])  -- 남은 TTL 반환 (락 획득 실패)
```

#### Watch Dog 자동 연장

```
┌─────────────────────────────────────────────────────────┐
│  Redisson Watch Dog (백그라운드 스레드)                    │
│                                                         │
│  leaseTime이 명시되지 않으면 자동 활성화 (기본 30초)         │
│  락 보유 중 leaseTime/3 (10초) 마다 TTL 갱신              │
│                                                         │
│  [비즈니스 로직 10초 경과] → TTL 30초로 갱신                │
│  [비즈니스 로직 20초 경과] → TTL 30초로 갱신                │
│  [비즈니스 로직 완료] → 락 해제                            │
└─────────────────────────────────────────────────────────┘
```

#### 왜 Redis인가?

| 특성 | 설명 |
|-----|------|
| **싱글 스레드** | 명령이 순차 처리되어 Race Condition 없음 |
| **Lua 스크립트** | 여러 명령을 원자적으로 실행 |
| **고성능** | 메모리 기반, 밀리초 단위 응답 |
| **Pub/Sub** | 락 해제 시 대기 클라이언트에 즉시 알림 |

---

### 4.2 분산 락 구현 방법 비교

#### Redis 기반

| 방식 | 장점 | 단점 |
|------|------|------|
| **Redisson** | 사용 편리, Watch Dog, 다양한 락 타입 | 의존성 추가 |
| **Lettuce + SETNX** | 추가 의존성 없음 | 직접 구현 필요 |
| **Spring Integration** | Spring 통합 | 기능 제한적 |

#### 데이터베이스 기반

| 방식 | 장점 | 단점 |
|------|------|------|
| **SELECT FOR UPDATE** | 추가 인프라 불필요 | DB 부하, 데드락 위험 |
| **MySQL Named Lock** | 간단한 구현 | MySQL 전용 |
| **ShedLock** | 배치 작업에 적합 | 단건 요청에는 과함 |

#### 대안 코드 예시

**낙관적 락 (JPA @Version)**

```kotlin
@Entity
class Product(
    @Id val id: Long,
    var stockQuantity: Int,

    @Version  // 버전 필드 추가
    var version: Long = 0
)

// 사용
fun decreaseStock(productId: Long, quantity: Int) {
    val product = productRepository.findById(productId)
    product.stockQuantity -= quantity
    try {
        productRepository.save(product)  // 버전 불일치 시 예외
    } catch (e: OptimisticLockingFailureException) {
        throw BusinessException(ErrorCode.CONCURRENT_UPDATE)
    }
}
```

**비관적 락 (SELECT FOR UPDATE)**

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
fun findByIdWithLock(id: Long): Product?
```

**Lettuce (Spring Data Redis)**

```kotlin
fun <T> executeWithLock(key: String, leaseTime: Duration, action: () -> T): T {
    val lockKey = "lock:$key"
    val lockValue = UUID.randomUUID().toString()

    val acquired = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, lockValue, leaseTime)  // SETNX + EXPIRE

    if (acquired != true) {
        throw BusinessException(ErrorCode.LOCK_ACQUISITION_FAILED)
    }

    try {
        return action()
    } finally {
        // Lua 스크립트로 본인 락만 해제
        val script = """
            if redis.call('get', KEYS[1]) == ARGV[1] then
                return redis.call('del', KEYS[1])
            end
            return 0
        """
        redisTemplate.execute(RedisScript.of(script, Long::class.java), listOf(lockKey), lockValue)
    }
}
```

---

### 4.3 락 전략 선택 가이드

#### 비교표

| 구분 | 낙관적 락 | 비관적 락 | 분산 락 |
|------|----------|----------|---------|
| **방식** | 버전 체크 후 충돌 시 실패 | SELECT FOR UPDATE | Redis/ZooKeeper |
| **충돌 처리** | 애플리케이션 재시도 | DB 대기열 관리 | 외부 시스템 관리 |
| **락 범위** | 단일 레코드 | 단일 레코드/테이블 | 자유롭게 정의 |
| **구현** | `@Version` | `@Lock(PESSIMISTIC)` | Redisson 등 |

#### 의사결정 플로우차트

```
                        시작
                          │
                          ▼
                  ┌───────────────┐
                  │ 충돌이 자주    │
                  │ 발생하는가?    │
                  └───────────────┘
                     │         │
                  아니오        예
                     │         │
                     ▼         ▼
              ┌──────────┐  ┌───────────────┐
              │ 낙관적 락 │  │ 락 보유 시간이  │
              │   사용    │  │ 긴가? (>100ms) │
              └──────────┘  └───────────────┘
                               │         │
                            아니오        예
                               │         │
                               ▼         ▼
                        ┌──────────┐  ┌──────────┐
                        │  DB가    │  │ 분산 락  │
                        │  단일?   │  │   사용   │
                        └──────────┘  └──────────┘
                           │    │
                          예   아니오
                           │    │
                           ▼    ▼
                     ┌─────────┐ ┌─────────┐
                     │ 비관적  │ │ 분산 락 │
                     │ 락 사용 │ │   사용  │
                     └─────────┘ └─────────┘
```

#### 실무 권장

| 서비스 | 권장 방식 | 이유 |
|--------|----------|------|
| 게시글 수정 | 낙관적 락 | 동시 수정 거의 없음 |
| 좋아요 카운트 | 없음/원자적 UPDATE | 정확도보다 성능 |
| **재고 차감** | **원자적 UPDATE** | DB 레벨에서 해결 |
| **중복 주문 방지** | **멱등성 키** | 가볍고 효과적 |
| **캐시 갱신** | **분산 락** | 스탬피드 방지 |
| **배치 작업** | **분산 락** | 다중 인스턴스 |
| **외부 API 연동** | **분산 락** | 직렬화 필요 |

#### 이 프로젝트에서 분산 락을 사용한 이유

> **참고**: 주문 생성에 분산 락을 적용한 것은 **학습/데모 목적**입니다.

**학습 목적:**
1. Redisson 분산 락 동작 원리 이해
2. AOP 기반 어노테이션 구현 실습
3. Redis 기반 동시성 제어 경험

**실무에서는:**
- 재고/쿠폰 → 원자적 UPDATE
- 중복 주문 → 멱등성 키
- 특수한 경우만 분산 락 (캐시, 배치, 외부 API)

> **실무 팁**: 가장 단순한 해결책부터 시작하세요. 원자적 UPDATE → 멱등성 키 → 분산 락 순으로 검토하고, 꼭 필요한 경우에만 복잡한 솔루션을 도입하세요.

---

# Part 2: Resilience 패턴

## 5. Circuit Breaker (서킷 브레이커)

### 5.1 서킷 브레이커란?

전기 회로의 **차단기**에서 유래. 장애가 발생한 서비스에 대한 요청을 **차단**하여 전체 시스템 붕괴 방지.

### 5.2 상태 다이어그램

```
             ┌──────────────┐
             │    CLOSED    │ ← 정상 상태
             │  (정상 운영)  │
             └──────┬───────┘
                    │
        실패율 50% 초과 ⚡
                    │
                    ▼
             ┌──────────────┐
             │     OPEN     │ ← 차단 상태 (모든 요청 즉시 실패)
             │  (요청 차단)  │
             └──────┬───────┘
                    │
            10초 대기 후
                    │
                    ▼
             ┌──────────────┐
             │  HALF-OPEN   │ ← 테스트 상태 (3개만 허용)
             │  (일부 허용)  │
             └──────┬───────┘
                    │
          ┌────────┴────────┐
          ▼                 ▼
     성공률 높음         실패 지속
          │                 │
          ▼                 ▼
     CLOSED 복귀       OPEN 복귀
```

### 5.3 설정

```yaml
resilience4j:
  circuitbreaker:
    instances:
      orderService:
        sliding-window-size: 10              # 최근 10개 요청 기준
        failure-rate-threshold: 50           # 실패율 50% 초과 시 OPEN
        wait-duration-in-open-state: 10s     # OPEN 상태 10초 유지
        permitted-number-of-calls-in-half-open-state: 3
        slow-call-duration-threshold: 2s     # 2초 이상 = 느린 호출
        slow-call-rate-threshold: 50         # 느린 호출 50% 초과 시 OPEN
        ignore-exceptions:                   # ⚠️ 중요!
          - com.example.marketplace.common.BusinessException
```

### 5.4 비즈니스 예외 vs 인프라 장애 (중요!)

**문제**: 서킷 브레이커가 서비스 레벨에서 동작하면, 한 상품의 재고 소진이 **다른 상품 주문까지 차단**

```
상품 A 재고 소진 → INSUFFICIENT_STOCK 다수 발생
        ↓
실패율 50% 초과 → Circuit Breaker OPEN
        ↓
❌ 상품 B, C 주문도 전부 차단됨!
```

**해결**: `ignore-exceptions`로 비즈니스 예외 제외

```yaml
ignore-exceptions:
  - com.example.marketplace.common.BusinessException
```

| 예외 유형 | 예시 | 서킷 브레이커 | 이유 |
|----------|------|-------------|------|
| **비즈니스 예외** | 재고 부족, 권한 없음 | ✅ 무시 | 정상적인 비즈니스 흐름 |
| **인프라 장애** | DB 연결 실패, 타임아웃 | ⚡ 카운트 | 시스템 장애, 보호 필요 |

---

## 6. Bulkhead (벌크헤드)

### 6.1 벌크헤드란?

선박의 **격벽**에서 유래. 한 구역이 침수되어도 다른 구역으로 퍼지지 않도록 **격리**.

```
┌───────────────────────────────────────────────────────────┐
│                      시스템 (선박)                          │
│  ┌─────────────┬─────────────┬─────────────┬─────────────┐ │
│  │  주문 서비스  │  결제 서비스  │  상품 서비스  │  회원 서비스  │ │
│  │  💧 과부하!  │  🛡️ 안전    │  🛡️ 안전    │  🛡️ 안전    │ │
│  └─────────────┴─────────────┴─────────────┴─────────────┘ │
│                    격벽(Bulkhead)으로 격리                   │
└───────────────────────────────────────────────────────────┘
```

### 6.2 설정

```yaml
resilience4j:
  bulkhead:
    instances:
      orderService:
        max-concurrent-calls: 20   # 동시 최대 20개
        max-wait-duration: 0s      # 대기 없이 즉시 거부
```

**효과**: 주문 폭주해도 다른 서비스는 정상 운영!

---

## 7. Retry (재시도)

### 7.1 설정

```yaml
resilience4j:
  retry:
    instances:
      orderService:
        max-attempts: 3               # 최대 3회 시도
        wait-duration: 500ms          # 재시도 간 500ms 대기
        retry-exceptions:             # 이 예외들만 재시도
          - java.io.IOException
          - java.util.concurrent.TimeoutException
```

### 7.2 지수 백오프

```yaml
retry:
  instances:
    orderService:
      enable-exponential-backoff: true
      exponential-backoff-multiplier: 2
      # 1초 → 2초 → 4초 → 8초 (점점 간격 증가)
```

### 7.3 재시도하면 안 되는 경우

| 예외 유형 | 재시도? | 이유 |
|----------|:------:|------|
| `IOException`, `TimeoutException` | ✅ | 일시적 네트워크 오류 |
| `INSUFFICIENT_STOCK` | ❌ | 재시도해도 결과 동일 |
| `INVALID_REQUEST` (400) | ❌ | 요청 자체가 잘못됨 |
| `UNAUTHORIZED` (401) | ❌ | 인증 필요 |
| `DUPLICATE_ORDER` | ❌ | 이미 처리된 요청 |
| `OutOfMemoryError` | ❌ | 시스템 문제, 재시도 무의미 |

```yaml
retry:
  instances:
    orderService:
      retry-exceptions:           # 이 예외들만 재시도
        - java.io.IOException
        - java.util.concurrent.TimeoutException
      ignore-exceptions:          # 이 예외들은 재시도 안 함
        - com.example.marketplace.common.BusinessException
```

> **주의**: 재시도 가능한 작업인지 확인하세요. 결제 API처럼 부작용이 있는 작업은 멱등성이 보장되지 않으면 재시도가 위험합니다.

---

# Part 3: 통합 및 운영

## 8. 전체 동작 흐름

### 8.1 주문 생성 프로세스

```
POST /api/v1/orders
      │
      ▼
┌─────────────────────────────────────────────────────┐
│ 1. Rate Limiter    │ 초당 10개 초과? → 429          │
├────────────────────┼────────────────────────────────┤
│ 2. Bulkhead        │ 동시 20개 초과? → 503          │
├────────────────────┼────────────────────────────────┤
│ 3. Circuit Breaker │ OPEN 상태? → 503               │
├────────────────────┼────────────────────────────────┤
│ 4. Distributed Lock│ 락 획득 실패? → 409            │
├────────────────────┼────────────────────────────────┤
│ 5. 비즈니스 로직    │ 재고 부족? → 409               │
│    - 원자적 재고 감소                                │
│    - 주문 생성                                      │
├────────────────────┼────────────────────────────────┤
│ 6. Lock 해제       │                                │
└─────────────────────────────────────────────────────┘
      │
      ▼
   응답 반환
```

### 8.2 AOP 프록시 체인 순서

> **중요**: 어노테이션 작성 순서는 AOP 적용 순서와 **무관**합니다!

#### 순서 결정 기준

| 우선순위 | 방법 | 설명 |
|---------|------|------|
| 1 | `@Order` 어노테이션 | Aspect 클래스에 지정 |
| 2 | `Ordered` 인터페이스 | 숫자가 작을수록 바깥쪽 |

#### 현재 프로젝트의 순서

```
요청 → DistributedLock → Retry → CircuitBreaker → RateLimiter → Bulkhead → @Transactional → 메서드
       (HIGHEST)         (-3)      (-2)            (-1)         (0)        (LOWEST)
```

```
┌─────────────────────────────────────┐
│ DistributedLock (가장 바깥)          │  ← 락 획득
│  ┌─────────────────────────────────┐│
│  │ Retry                           ││  ← 재시도 래핑
│  │  ┌─────────────────────────────┐││
│  │  │ CircuitBreaker              │││  ← 서킷 체크
│  │  │  ┌─────────────────────────┐│││
│  │  │  │ Bulkhead                ││││  ← 동시 요청 제한
│  │  │  │  ┌─────────────────────┐││││
│  │  │  │  │ @Transactional      │││││  ← 트랜잭션
│  │  │  │  │  ┌─────────────────┐││││││
│  │  │  │  │  │  실제 메서드     │││││││
│  │  │  │  │  └─────────────────┘││││││
│  │  │  │  └─────────────────────┘│││││
│  │  │  └─────────────────────────┘││││
│  │  └─────────────────────────────┘│││
│  └─────────────────────────────────┘││
└─────────────────────────────────────┘│
```

---

## 9. 설정 값 가이드

### 9.1 분산 락 설정

| 설정 | 기본값 | 권장 범위 | 설명 |
|-----|-------|----------|------|
| `waitTime` | 5초 | 1-10초 | 락 획득 대기 시간 |
| `leaseTime` | 30초 | 로직시간×2~3 | 락 자동 해제 시간 |

#### waitTime 설정 기준

**핵심**: 사용자가 허용 가능한 최대 대기 시간

```
예시) 주문 처리
- 처리 시간: ~200ms
- 동시 요청: 최대 10개
- 최악: 200ms × 10 = 2초
- 여유 포함: 5초 설정
```

#### leaseTime 설정 기준

**핵심**: `leaseTime > (예상 최대 수행 시간 × 2~3)`

```kotlin
@DistributedLock(key = "...", waitTime = 5, leaseTime = 30)
fun createOrder(...) {
    // 정상: ~250ms
    // 최악 (DB 부하, 네트워크 지연): ~10초
    // leaseTime = 10초 × 3 = 30초
}
```

#### 작업 유형별 권장 값

| 작업 유형 | waitTime | leaseTime |
|----------|----------|-----------|
| 캐시 갱신 | 1-2초 | 5초 |
| **주문/결제** | **5초** | **30초** |
| 배치 작업 | 10초 | 5분 |
| 외부 API 연동 | 10초 | 60초 |

### 9.2 서킷 브레이커 설정

| 설정 | 값 | 설명 |
|-----|---|------|
| `sliding-window-size` | 10 | 실패율 계산 기준 요청 수 |
| `failure-rate-threshold` | 50% | OPEN 전환 기준 |
| `wait-duration-in-open-state` | 10초 | OPEN 유지 시간 |
| `ignore-exceptions` | BusinessException | **무시할 예외 (중요!)** |

### 9.3 벌크헤드/재시도 설정

| 구분 | 설정 | 값 |
|------|-----|---|
| **Bulkhead** | max-concurrent-calls | 20 |
| | max-wait-duration | 0초 (즉시 거부) |
| **Retry** | max-attempts | 3 |
| | wait-duration | 500ms |

---

## 10. 테스트 방법

### 10.1 k6 동시성 테스트

```javascript
// k6/concurrency-test.js
import http from 'k6/http';
import { check } from 'k6';

export let options = {
    vus: 10,
    duration: '5s',
};

export function setup() {
    let loginRes = http.post('http://localhost:8080/api/v1/auth/login',
        JSON.stringify({
            email: 'buyer@example.com',
            password: 'buyer123!'  // 실제 비밀번호
        }),
        { headers: { 'Content-Type': 'application/json' } }
    );
    return { token: JSON.parse(loginRes.body).data.accessToken };
}

export default function(data) {
    let orderRes = http.post('http://localhost:8080/api/v1/orders',
        JSON.stringify({
            orderItems: [{ productId: 2, quantity: 1 }],
            shippingAddress: {
                zipCode: '12345', address: 'Test', addressDetail: 'Apt',
                receiverName: 'Test', receiverPhone: '010-1234-5678'
            }
        }),
        { headers: {
            'Content-Type': 'application/json',
            'Authorization': `Bearer ${data.token}`
        }}
    );

    check(orderRes, {
        'status is 200 or 409': (r) => r.status === 200 || r.status === 409
    });
}
```

실행: `k6 run k6/concurrency-test.js`

### 10.2 Redis 락 확인

```bash
docker exec -it marketplace-redis redis-cli
> KEYS order:*
> HGETALL "order:create:1"
> TTL "order:create:1"
```

---

## 11. 운영 환경 문제 해결 가이드

### 11.1 Redis 관련 문제

#### 연결 실패 / 타임아웃

```
증상: RedisTimeoutException: Command execution timeout
탐지: redis_up == 0 또는 redis_command_duration_seconds > 1s
해결: 커넥션 풀 튜닝, HA 구성, 로컬 폴백
```

#### 락 데드락

```
증상: 특정 사용자 주문이 계속 실패
탐지: redis-cli KEYS "lock:*" / TTL 확인
해결: 관리자 API로 강제 해제 (lock.forceUnlock())
```

### 11.2 데이터베이스 관련 문제

#### 커넥션 풀 고갈

```
증상: HikariPool - Connection is not available
탐지: hikaricp_connections_pending > 0
해결: pool-size 증가, 트랜잭션 범위 최소화
```

#### 슬로우 쿼리

```
탐지: slow_query_log, Hibernate SQL 로깅
해결: 인덱스 추가, N+1 해결
```

### 11.3 동시성 관련 문제

#### 락 획득 실패 급증

```
탐지: distributed_lock{result="failed"} 메트릭
해결: waitTime 증가, 락 키 세분화
```

#### 재고 음수 (Overselling)

```
탐지: SELECT * FROM products WHERE stock_quantity < 0
해결: DB CHECK 제약조건 추가
```

### 11.4 필수 모니터링 항목

```
┌─────────────────────────────────────────────────────────┐
│                   System Overview                        │
├─────────────────┬─────────────────┬─────────────────────┤
│ Request Rate    │ Error Rate      │ P99 Latency         │
│ [====] 1.2k/s   │ [==] 0.5%       │ [======] 245ms      │
├─────────────────┴─────────────────┴─────────────────────┤
│                   Database                               │
│ Active Conns: 15/50  │  Pending: 2  │  Slow: 3/min      │
├─────────────────────────────────────────────────────────┤
│                   Redis                                  │
│ Memory: 2.1GB  │  Connected: ✓  │  Lock Failures: 5/min │
├─────────────────────────────────────────────────────────┤
│                   Circuit Breakers                       │
│ orderService: CLOSED ✓  │  paymentService: OPEN ⚠️      │
└─────────────────────────────────────────────────────────┘
```

### 11.5 필수 알람

```yaml
- alert: HighErrorRate
  expr: rate(http_server_requests_seconds_count{status=~"5.."}[5m]) > 0.1

- alert: CircuitBreakerOpen
  expr: resilience4j_circuitbreaker_state == 1

- alert: ConnectionPoolExhaustion
  expr: hikaricp_connections_pending > 5

- alert: RedisDown
  expr: redis_up == 0
```

### 11.6 장애 대응 체크리스트

```
□ 1. 장애 인지 → Slack/PagerDuty 알림 확인
□ 2. 영향 범위 파악 → 에러율, 영향 사용자 수
□ 3. 원인 파악 → 최근 배포, 로그, 메트릭
□ 4. 긴급 대응 → 롤백, Rate Limit, 서킷 수동 OPEN
□ 5. 복구 확인 → 메트릭 정상화, 테스트 주문
□ 6. 포스트모템 → 타임라인, 근본 원인, 재발 방지
```

### 11.7 실제 장애 사례

#### 사례 1: 플래시 세일 시 락 실패 폭발

```
원인: 모든 요청이 같은 락 키 경쟁
해결: 사용자별 락 분리 + 원자적 업데이트
      @DistributedLock(key = "'sale:' + #productId + ':' + #userId")
```

#### 사례 2: 결제 장애로 전체 주문 실패

```
원인: 타임아웃 30초, slow-call 미설정
해결: slow-call-duration-threshold: 3s 추가, 타임아웃 5s로 단축
```

#### 사례 3: 배치로 인한 커넥션 고갈

```
원인: 배치가 커넥션 50개 중 45개 점유
해결: API용/배치용 별도 데이터소스 분리
```

---

## 요약

### 동시성 제어

| 문제 | 권장 해결책 | 비고 |
|-----|------------|------|
| **재고 과잉 판매** | **원자적 UPDATE** | `UPDATE WHERE stock >= qty` |
| **쿠폰 중복 사용** | **원자적 UPDATE** | `UPDATE WHERE used = false` |
| **중복 주문 (따닥)** | **멱등성 키** | 분산 락은 오버엔지니어링 |
| **캐시 스탬피드** | **분산 락** | DB 보호 목적 |
| **배치 중복 실행** | **분산 락** | 다중 인스턴스 환경 |

### Resilience 패턴

| 문제 | 해결책 | 핵심 기능 |
|-----|--------|----------|
| **장애 전파** | **Circuit Breaker** | 실패율 높으면 차단 |
| **리소스 고갈** | **Bulkhead** | 동시 요청 수 제한 |
| **일시적 오류** | **Retry** | 자동 재시도 |

### 주문 요청 흐름 (권장)

```
┌──────────────────────────────────────────────────────────────┐
│                                                              │
│   요청 → [Rate Limiter] → [Bulkhead] → [Circuit Breaker]     │
│            초당 제한       동시 제한      장애 차단            │
│                              │                               │
│                              ▼                               │
│         [멱등성 체크] → [원자적 UPDATE] → 주문 완료            │
│          중복 요청 방지     재고/쿠폰 보호                     │
│                                                              │
│   ※ 분산 락: 캐시 스탬피드, 배치, 외부 API 연동 시에만 사용     │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 핵심 메시지

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  ✅ 원자적 UPDATE만으로 대부분의 데이터 무결성 문제 해결       │
│                                                             │
│  ✅ 중복 요청 방지는 멱등성 키가 더 가볍고 효과적             │
│                                                             │
│  ⚠️ 분산 락은 특수한 경우에만 필요:                          │
│     - 캐시 스탬피드 방지                                     │
│     - 배치 작업 중복 실행 방지                               │
│     - 외부 API 직렬화 제약                                   │
│     - 장시간 리소스 선점 (좌석 예약 등)                       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

> **실무 팁**: 단순한 시스템에서는 원자적 UPDATE + 멱등성 키로 시작하고, 필요할 때만 분산 락을 도입하세요.

---

## FAQ (자주 묻는 질문)

### Q1. 분산 락 없이 재고 보호가 정말 되나요?

**A**: 네, 원자적 UPDATE만으로 충분합니다.

```sql
UPDATE products SET stock = stock - 1 WHERE id = 1 AND stock >= 1
```

DB의 Row Lock이 동시성을 처리합니다. 분산 락은 재고 보호가 아닌 다른 목적(캐시, 배치 등)에 사용됩니다.

---

### Q2. 원자적 UPDATE가 실패하면 어떻게 되나요?

**A**: `affected rows = 0`이 반환되고, 애플리케이션에서 예외를 던집니다.

```kotlin
val updated = productRepository.decreaseStockAtomically(productId, quantity)
if (updated == 0) throw BusinessException(ErrorCode.INSUFFICIENT_STOCK)
```

---

### Q3. 여러 상품을 동시에 주문할 때 데드락이 발생하나요?

**A**: 상품 ID 순으로 정렬하여 UPDATE하면 데드락을 방지할 수 있습니다.

```kotlin
val items = orderItems.sortedBy { it.productId }  // 항상 같은 순서로 락 획득
items.forEach { productRepository.decreaseStockAtomically(it.productId, it.quantity) }
```

---

### Q4. 멱등성 키는 누가 생성하나요?

**A**: 일반적으로 **클라이언트**가 생성합니다. 서버에서 자동 생성하면 중복 요청을 구분할 수 없습니다.

```javascript
// 클라이언트
headers: { 'Idempotency-Key': crypto.randomUUID() }
```

버튼 클릭 시 키를 생성하고, 재시도 시 동일한 키를 사용합니다.

---

### Q5. 캐시 스탬피드가 뭔가요?

**A**: 캐시가 만료되는 순간 수천 개의 요청이 동시에 DB를 조회하는 현상입니다.

```
캐시 만료 → 1000개 요청이 캐시 미스 → 1000개 DB 쿼리 → DB 과부하
```

**해결**: 분산 락으로 1개 요청만 DB 조회, 나머지는 대기 후 캐시에서 읽기

---

### Q6. Circuit Breaker가 열리면 어떻게 되나요?

**A**: 모든 요청이 즉시 실패하고 fallback이 실행됩니다 (설정된 경우).

```kotlin
@CircuitBreaker(name = "orderService", fallbackMethod = "createOrderFallback")
fun createOrder(...) { ... }

fun createOrderFallback(request: OrderRequest, ex: Exception): OrderResponse {
    throw BusinessException(ErrorCode.SERVICE_TEMPORARILY_UNAVAILABLE)
}
```

---

### Q7. 비즈니스 예외도 Circuit Breaker에 카운트되나요?

**A**: `ignore-exceptions`에 등록하면 카운트되지 않습니다.

```yaml
ignore-exceptions:
  - com.example.marketplace.common.BusinessException
```

재고 부족(`INSUFFICIENT_STOCK`)은 정상적인 비즈니스 흐름이므로 서킷을 열면 안 됩니다.

---

### Q8. 언제 분산 락을 써야 하나요?

**A**: 아래 경우에만 사용하세요:

| 사용 O | 사용 X |
|--------|--------|
| 캐시 스탬피드 방지 | 재고 차감 |
| 배치 작업 중복 방지 | 쿠폰 사용 |
| 외부 API 직렬화 | 중복 주문 방지 |
| 좌석/리소스 장시간 선점 | 일반적인 CRUD |

**판단 기준**: "원자적 UPDATE나 멱등성 키로 해결 가능한가?" → 가능하면 분산 락 불필요
