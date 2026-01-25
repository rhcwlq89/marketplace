# 캐싱 패턴 상세 가이드

> **이 문서의 대상**: 마켓플레이스 시스템의 캐싱 전략을 이해하고자 하는 백엔드 개발자

---

## TL;DR (핵심 요약)

```
캐시 도입 = 문제가 발생했을 때 (섣부른 최적화 금지)
기본 전략 = Cache-Aside + DTO + 짧은 TTL
데이터별 전략 = 특성에 따라 다르게 (단일 전략 X)
```

| 데이터 특성 | 권장 전략 | TTL | 예시 |
|------------|----------|-----|------|
| **거의 안 변함** | Read-Through + Refresh-Ahead | 1시간~1일 | 카테고리, 설정 |
| **가끔 변함** | Cache-Aside + 명시적 무효화 | 5~30분 | 상품 정보 |
| **자주 변함** | 캐시 안 함 | - | 재고, 결제 상태 |
| **쓰기 많음** | Write-Behind | 배치 | 조회수, 좋아요 |
| **계산 비용 높음** | Cache-Aside + 긴 TTL | 5분~1시간 | 랭킹, 통계 |

> **핵심 원칙**: Entity 캐싱은 안티패턴! 반드시 DTO로 변환 후 캐싱

---

## 목차

### Part 1: 캐시 기본 개념
1. [캐시란 무엇인가?](#1-캐시란-무엇인가)
2. [캐시 도입 시점](#2-캐시-도입-시점)
3. [데이터 특성별 전략 선택](#3-데이터-특성별-전략-선택)

### Part 2: 캐싱 패턴
4. [Cache-Aside (Lazy Loading)](#4-cache-aside-lazy-loading)
5. [Read-Through](#5-read-through)
6. [Write-Through](#6-write-through)
7. [Write-Behind (Write-Back)](#7-write-behind-write-back)
8. [Refresh-Ahead](#8-refresh-ahead)

### Part 3: 캐시 문제와 해결책
9. [캐시 무효화 전략](#9-캐시-무효화-전략)
10. [Cache Stampede (Thundering Herd)](#10-cache-stampede-thundering-herd)
11. [Cache Penetration](#11-cache-penetration)
12. [Cache Avalanche](#12-cache-avalanche)
13. [Hot Key 문제](#13-hot-key-문제)

### Part 4: 운영 및 모니터링
14. [로컬 캐시 vs 분산 캐시](#14-로컬-캐시-vs-분산-캐시)
15. [프로젝트 적용 사례](#15-프로젝트-적용-사례)
16. [모니터링 및 운영](#16-모니터링-및-운영)
17. [FAQ (자주 묻는 질문)](#faq-자주-묻는-질문)

---

# Part 1: 캐시 기본 개념

## 1. 캐시란 무엇인가?

자주 접근하는 데이터를 빠른 저장소에 보관하여 응답 시간을 단축하고 DB 부하를 줄이는 기술입니다.

### 1.1 응답 시간 비교

```
┌─────────────────────────────────────────────────────────────┐
│  저장소별 응답 시간                                           │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  DB 조회:     ~10ms   (네트워크 + 디스크 I/O)                │
│  Redis 조회:  ~1ms    (네트워크 + 메모리)                    │
│  로컬 캐시:   ~0.01ms (메모리만)                             │
│                                                             │
│  ※ 로컬 캐시는 Redis보다 100배 빠름                          │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

### 1.2 캐시 효과 계산

```
QPS 1000, DB 조회 10ms 가정:

캐시 없음:     1000 × 10ms = 10초/초의 DB 부하
캐시 90% 히트: 100 × 10ms = 1초/초의 DB 부하 (10배 감소!)
```

### 1.3 캐시 적합성 판단

| 적합한 데이터 | 부적합한 데이터 |
|--------------|----------------|
| 자주 읽히는 데이터 | 자주 변경되는 데이터 |
| 변경이 드문 데이터 | 실시간 정확성 필수 데이터 |
| 계산 비용이 높은 데이터 | 사용자별 민감 데이터 |
| 공유 가능한 데이터 | 일회성 데이터 |

```
마켓플레이스 예시:

✅ 적합: 상품 목록, 카테고리, 인기 상품, 설정값
❌ 부적합: 재고 수량, 결제 상태, 실시간 가격
```

---

## 2. 캐시 도입 시점

> **핵심**: 캐시는 "있으면 좋다"가 아니라 **문제가 발생했을 때** 도입합니다.

### 2.1 도입 신호 (이런 상황이면 검토)

```
1. DB CPU 사용률 지속 70% 이상
2. 동일 쿼리가 반복 실행됨 (슬로우 쿼리 로그 분석)
3. API 응답 시간 SLA 미달 (예: p95 > 500ms)
4. DB 커넥션 풀 고갈 현상
5. 트래픽 급증 예정 (이벤트, 프로모션)
```

### 2.2 SLA/SLO/SLI 용어 정리

| 용어 | 의미 | 예시 |
|------|------|------|
| **SLI** (Indicator) | 실제 측정값 | p95 응답시간 = 320ms |
| **SLO** (Objective) | 내부 목표 | p95 < 500ms |
| **SLA** (Agreement) | 외부 약속 (위반 시 보상) | p95 < 1000ms |

```
SLA 예시:

[응답 시간]
- p50: 100ms 이하   (50%의 요청이 100ms 안에 응답)
- p95: 500ms 이하   (95%의 요청이 500ms 안에 응답)
- p99: 1000ms 이하  (99%의 요청이 1초 안에 응답)

[가용성]
- 99.9%  → 한 달에 약 43분 다운타임 허용
- 99.99% → 한 달에 약 4분 다운타임 허용
```

### 2.3 도입 판단 플로우

```
                        시작
                          │
                          ▼
                  ┌───────────────┐
                  │ 응답 시간이    │
                  │ 느린가?       │
                  └───────────────┘
                     │         │
                   YES        NO
                     │         │
                     ▼         ▼
              ┌──────────┐   캐시 불필요
              │ 원인이   │   (섣부른 최적화 금지)
              │ DB인가?  │
              └──────────┘
                 │    │
               YES   NO
                 │    │
                 ▼    ▼
          ┌──────────┐  다른 병목 해결
          │ 쿼리 최적화│  (네트워크, 외부 API)
          │ 가능한가?  │
          └──────────┘
             │    │
           YES   NO
             │    │
             ▼    ▼
       인덱스/쿼리  ┌──────────┐
       튜닝 먼저    │ 읽기:쓰기 │
                   │ > 10:1?  │
                   └──────────┘
                      │    │
                    YES   NO
                      │    │
                      ▼    ▼
                 캐시 도입  DB 스케일업 검토
```

### 2.4 도입 전 체크리스트

```
□ 현재 병목 지점을 측정했는가? (APM, 슬로우 쿼리 로그)
□ 캐시 없이 해결 가능한 방법을 검토했는가? (인덱스, 쿼리 최적화)
□ 캐시할 데이터의 읽기/쓰기 비율을 파악했는가?
□ 데이터 불일치 허용 범위를 정의했는가?
□ 캐시 장애 시 fallback 전략이 있는가?
□ 캐시 히트율 모니터링 계획이 있는가?
```

### 2.5 도입하지 말아야 할 상황

```
❌ "나중에 트래픽 많아지면 필요하니까" → 섣부른 최적화
❌ "다른 회사도 Redis 쓰니까" → 근거 없는 도입
❌ 쓰기가 읽기보다 많은 데이터 → 캐시 효과 미미
❌ 실시간 정확성이 필수인 데이터 → 재고, 결제 상태
❌ 사용자별로 다른 데이터 → 캐시 히트율 낮음
```

### 2.6 단계별 도입 전략

```
[1단계] 로컬 캐시로 시작 (Caffeine)
        - 인프라 추가 없이 즉시 적용
        - 단일 서버 또는 데이터 불일치 허용 시

[2단계] 분산 캐시 전환 (Redis)
        - 다중 서버 환경
        - 데이터 일관성 필요 시

[3단계] 다단계 캐시 구성 (Caffeine + Redis)
        - Hot 데이터는 로컬, 전체는 Redis
        - 최적의 성능 필요 시
```

---

## 3. 데이터 특성별 전략 선택

> **핵심**: 단일 전략으로 모든 데이터를 처리하지 마세요. 특성에 따라 다르게!

### 3.1 특성별 권장 전략

| 데이터 특성 | 예시 | 권장 전략 | TTL |
|------------|------|----------|-----|
| **거의 안 변함** | 카테고리, 약관, 설정 | Read-Through + Refresh-Ahead | 1시간~1일 |
| **가끔 변함** | 상품 정보, 프로필 | Cache-Aside + 명시적 무효화 | 5~30분 |
| **자주 변함** | 재고, 가격 | 캐시 안 함 또는 매우 짧은 TTL | 10~30초 |
| **쓰기가 많음** | 조회수, 좋아요 | Write-Behind | N/A (배치) |
| **계산 비용 높음** | 통계, 랭킹, 집계 | Cache-Aside + 긴 TTL | 5분~1시간 |

### 3.2 마켓플레이스 적용 예시

```
[카테고리 목록] ← 실제 적용됨
- 특성: 거의 안 변함, 모든 페이지에서 사용
- 전략: Cache-Aside (@Cacheable + @CacheEvict)
- TTL: 10분
- 코드: CategoryService.getAllCategories()

[인기 상품 TOP 10] ← 실제 적용됨
- 특성: 계산 비용 높음, 약간의 지연 허용
- 전략: Cache-Aside (@Cacheable + @CacheEvict)
- TTL: 10분
- 코드: ProductService.getPopularProducts()

[상품 재고]
- 특성: 실시간 정확성 필요
- 전략: 캐시 안 함 (원자적 UPDATE로 DB 직접 조회)
- 코드: ProductJpaRepositoryImpl.decreaseStockAtomically()

[상품 상세] ← 필요 시 적용 가능
- 특성: 가끔 변함, 읽기 많음
- 전략: Cache-Aside + @CacheEvict
- TTL: 5~10분

[상품 조회수] ← 필요 시 적용 가능
- 특성: 쓰기 매우 많음, 정확도 덜 중요
- 전략: Write-Behind (Redis 카운터 → 배치로 DB 동기화)

[검색 결과]
- 특성: 조합이 많아 히트율 낮음
- 전략: 캐시 안 함 (현재 적용 안 함)
```

### 3.3 판단 기준

```
1. 읽기:쓰기 비율
   - 100:1 이상 → 적극 캐싱
   - 10:1 정도 → 선택적 캐싱
   - 1:1 이하 → 캐싱 효과 미미

2. 불일치 허용 범위
   - 허용 불가 (재고, 결제) → 캐시 안 함
   - 수 초 허용 → 짧은 TTL (10~30초)
   - 수 분 허용 → 일반 TTL + 무효화

3. 접근 패턴
   - Hot Data (인기 상품) → 로컬 캐시 + Redis (다단계)
   - Cold Data (오래된 상품) → Redis만 또는 캐시 안 함

4. 계산 비용
   - 단순 조회 → 캐시 효과 작음
   - 집계/정렬/조인 → 캐시 효과 큼
```

---

# Part 2: 캐싱 패턴

## 4. Cache-Aside (Lazy Loading)

**가장 널리 사용되는 패턴.** 애플리케이션이 캐시와 DB를 직접 관리합니다.

> **마켓플레이스 적용**: 카테고리 목록, 인기 상품 TOP 10에 적용됨 → [15. 프로젝트 적용 사례](#15-프로젝트-적용-사례) 참조

### 4.1 동작 방식

```
[읽기 - Cache Hit]
Client → App → Cache (HIT) → 데이터 반환

[읽기 - Cache Miss]
Client → App → Cache (MISS) → DB 조회 → Cache 저장 → 데이터 반환

[쓰기]
Client → App → DB 저장 → Cache 무효화 (또는 갱신)
```

### 4.2 Entity 캐싱은 안티패턴!

```kotlin
// ❌ 잘못된 예시: Entity 직접 캐싱
@Cacheable(value = ["products"], key = "#id")
fun getProduct(id: Long): Product {  // Entity 반환
    return productRepository.findById(id).orElseThrow()
}
```

**Entity 캐싱이 문제인 이유:**

| 문제 | 설명 |
|------|------|
| Lazy Loading 오류 | 캐시에서 꺼낸 Entity는 영속성 컨텍스트 밖 → `LazyInitializationException` |
| 직렬화 문제 | Hibernate Proxy 객체 직렬화 실패 가능 |
| 불필요한 데이터 노출 | 내부 필드, 연관 Entity까지 캐싱/노출 |
| 캐시 크기 증가 | Entity 전체 저장 → 메모리 낭비 |
| 변경 감지 오작동 | 캐시된 Entity 수정 시 의도치 않은 DB 반영 |

### 4.3 올바른 구현 (DTO 사용)

```kotlin
// ✅ 올바른 예시: DTO 캐싱

// 1. 캐시용 DTO 정의
data class ProductCacheDto(
    val id: Long,
    val name: String,
    val price: BigDecimal,
    val status: ProductStatus,
    val stockQuantity: Int,
    val categoryId: Long,
    val categoryName: String
) {
    companion object {
        fun from(product: Product): ProductCacheDto {
            return ProductCacheDto(
                id = product.id!!,
                name = product.name,
                price = product.price,
                status = product.status,
                stockQuantity = product.stockQuantity,
                categoryId = product.category.id!!,
                categoryName = product.category.name
            )
        }
    }
}

// 2. Service에서 DTO 반환
@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val redisTemplate: RedisTemplate<String, ProductCacheDto>
) {
    // 직접 구현
    fun getProduct(id: Long): ProductCacheDto {
        val cacheKey = "product:$id"

        // 1. 캐시 조회
        redisTemplate.opsForValue().get(cacheKey)?.let { return it }

        // 2. Cache Miss → DB 조회 후 DTO 변환
        val product = productRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }

        val dto = ProductCacheDto.from(product)

        // 3. 캐시에 DTO 저장 (TTL 10분)
        redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofMinutes(10))

        return dto
    }

    // Spring @Cacheable 사용 (권장)
    @Cacheable(value = ["products"], key = "#id")
    fun getProductWithCache(id: Long): ProductCacheDto {
        val product = productRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        return ProductCacheDto.from(product)
    }

    // 캐시 무효화
    @CacheEvict(value = ["products"], key = "#id")
    fun updateProduct(id: Long, request: UpdateProductRequest): ProductResponse {
        val product = productRepository.findById(id)
            .orElseThrow { BusinessException(ErrorCode.PRODUCT_NOT_FOUND) }
        product.update(request.name, request.price, request.description)
        return ProductResponse.from(productRepository.save(product))
    }
}
```

### 4.4 DTO vs Entity 캐싱 비교

| 항목 | Entity 캐싱 | DTO 캐싱 |
|------|------------|---------|
| Lazy Loading | 오류 발생 | 문제 없음 |
| 직렬화 | Proxy 문제 | 안전 |
| 캐시 크기 | 큼 (전체 필드) | 작음 (필요한 것만) |
| API 응답 변환 | 추가 작업 필요 | 바로 사용 가능 |
| 연관 관계 | N+1 위험 | 미리 평탄화 |

### 4.5 장단점

| 장점 | 단점 |
|------|------|
| 구현이 단순함 | 첫 요청 시 Cache Miss (Cold Start) |
| 필요한 데이터만 캐싱 | Cache Miss 시 지연 발생 |
| 캐시 장애 시 DB로 fallback 가능 | 데이터 불일치 가능 (TTL 내) |

### 4.6 데이터 불일치 케이스 (중요!)

Cache-Aside는 데이터 불일치가 발생할 수 있습니다.

**케이스 1: 쓰기 후 읽기 경쟁 (가장 흔함)**

```
[요청 A: 상품 가격 수정]          [요청 B: 상품 조회]
         │                              │
         ├─ DB 업데이트 (1000 → 2000)   │
         │                              ├─ 캐시 조회 (HIT: 1000) ← 오래된 데이터!
         ├─ 캐시 삭제                   │
         │                              └─ 응답: 1000원
         └─ 완료
```

원인: DB 업데이트와 캐시 삭제 사이에 다른 요청이 캐시를 읽음

**케이스 2: 캐시 갱신 경쟁 조건 (상세)**

두 개의 **읽기 요청**이 거의 동시에 들어오고, 그 사이에 **쓰기 요청**이 끼어드는 상황입니다.

```
[요청 A]                           [요청 B]
   │                                  │
   ├─ 캐시 조회 (MISS)                ├─ 캐시 조회 (MISS)
   ├─ DB 조회 (가격: 1000)            ├─ DB 조회 (가격: 1000)
   │                                  │
   │  ← 이 시점에 다른 요청이 가격을 2000으로 수정 + 캐시 삭제 →
   │                                  │
   │                                  ├─ 캐시 저장 (1000) ← 삭제된 캐시에 옛날 값 저장!
   ├─ 캐시 저장 (1000)                │

결과: DB는 2000인데 캐시는 1000 (TTL까지 불일치)
```

**구체적인 타임라인:**

```
상품 ID: 123, 현재 가격: 1000원

[09:00:00.000] 사용자 A: 상품 123 조회 요청
[09:00:00.001] 사용자 B: 상품 123 조회 요청
[09:00:00.002] A: 캐시 MISS
[09:00:00.003] B: 캐시 MISS
[09:00:00.010] A: DB 조회 시작
[09:00:00.011] B: DB 조회 시작
[09:00:00.050] A: DB 조회 완료 (가격: 1000원)
[09:00:00.051] B: DB 조회 완료 (가격: 1000원)

[09:00:00.060] ★ 관리자: 가격 2000원으로 수정 + 캐시 삭제

[09:00:00.070] B: 캐시에 1000원 저장  ← 삭제된 캐시에 옛날 값 저장!
[09:00:00.071] A: 캐시에 1000원 저장  ← 덮어쓰기

[09:00:00.100 ~ 09:10:00.070]
    → TTL 동안 모든 사용자가 1000원으로 보게 됨 (실제는 2000원)
```

**왜 발생하는가?**

```kotlin
fun getProduct(id: Long): ProductCacheDto {
    // 1. 캐시 조회
    val cached = redisTemplate.opsForValue().get("product:$id")
    if (cached != null) return cached

    // 2. DB 조회 (여기서 시간이 걸림!)
    val product = productRepository.findById(id).orElseThrow()

    // ★ 이 사이에 다른 요청이 DB를 수정하고 캐시를 삭제할 수 있음!

    // 3. 캐시 저장 (오래된 데이터를 저장하게 됨)
    val dto = ProductCacheDto.from(product)
    redisTemplate.opsForValue().set("product:$id", dto, Duration.ofMinutes(10))

    return dto
}
```

핵심 문제: DB 조회(2단계)와 캐시 저장(3단계) 사이에 **시간 차**가 있고, 이 사이에 데이터가 변경될 수 있습니다.

**발생 빈도:**

```
발생 확률 ∝ (동시 요청 수) × (DB 조회 시간) × (데이터 변경 빈도)

고위험: 초당 1000 요청, DB 조회 50ms, 분당 10회 변경
저위험: 초당 10 요청, DB 조회 5ms, 일 1회 변경
```

> 케이스 1보다 덜 흔하지만, **캐시가 삭제된 후에도 오래된 데이터가 다시 저장**된다는 점에서 더 까다로운 문제입니다. 해결책은 4.7절 참조.

**케이스 3: 읽기-쓰기 동시 발생**

케이스 2와 유사하지만, 하나의 읽기 요청과 하나의 쓰기 요청이 겹치는 경우입니다.

```
시간   요청 A (조회)                    요청 B (수정)
─────┬─────────────────────────────────────────────────────
 T1  │ 캐시 조회 → MISS                     │
 T2  │ DB 조회 시작 (가격: 1000)            │
 T3  │      │                         DB 수정 (1000→2000)
 T4  │      │                         캐시 삭제 ✓
 T5  │ DB 조회 완료 (1000)                  │
 T6  │ 캐시 저장 (1000) ← 삭제된 캐시에 옛날 값 저장!
─────┴─────────────────────────────────────────────────────
결과: DB = 2000, 캐시 = 1000 (불일치!)
```

원인: 요청 A가 DB를 읽은 후 캐시에 저장하기 전에, 요청 B가 캐시를 삭제함

### 4.7 불일치 해결 방법

| 방법 | 설명 | 적합한 상황 |
|------|------|------------|
| **짧은 TTL** | 불일치 시간 최소화 (30초~1분) | 대부분의 경우 (권장) |
| **Write-Through** | 삭제 대신 갱신 (`@CachePut`) | 일관성 중요 |
| **지연 삭제** | 삭제 후 500ms 뒤 한 번 더 삭제 | 경쟁 조건 대비 (케이스 2, 3) |
| **분산 락** | 캐시 갱신 시 락 획득 | 강한 일관성 필요 |
| **버전 키** | `product:1:v5` 처럼 버전 포함 | 복잡하지만 확실 |

**지연 삭제 (Delayed Double Delete) - 케이스 2, 3 해결:**

```kotlin
@Transactional
fun updateProduct(id: Long, request: UpdateRequest): ProductResponse {
    // 1. 캐시 먼저 삭제
    redisTemplate.delete("product:$id")

    // 2. DB 업데이트
    val product = productRepository.save(...)

    // 3. 500ms 후 한 번 더 삭제 (경쟁 조건 방어)
    CompletableFuture.delayedExecutor(500, TimeUnit.MILLISECONDS).execute {
        redisTemplate.delete("product:$id")
    }

    return ProductResponse.from(product)
}
```

**왜 효과적인가?**

```
케이스 2 상황에서:

[09:00:00.060] 관리자: 가격 수정 + 캐시 삭제 (1차)
[09:00:00.070] B: 캐시에 1000원 저장 ← 오래된 값 저장됨
[09:00:00.560] 관리자: 캐시 삭제 (2차, 지연 삭제) ← 오래된 값 제거!
[09:00:00.600] 다음 요청: 캐시 MISS → DB 조회 (2000원) → 정상!
```

지연 삭제는 "DB 조회 → 캐시 저장" 사이의 시간(보통 50~200ms)보다
긴 시간(500ms) 후에 다시 삭제하여 오래된 값을 제거합니다.

> **실무 권장:** 대부분 **짧은 TTL**만으로 충분합니다.
> "TTL 동안 잠깐 옛날 데이터가 보여도 비즈니스에 문제없다"면 복잡한 해결책은 불필요합니다.
> 예: 상품 설명이 1분간 옛날 버전으로 보여도 큰 문제 아님
>
> 반면 **즉시 반영이 필수**인 경우(가격, 재고 등)에는 **지연 삭제**를 추가하세요.

---

## 5. Read-Through

캐시가 DB 조회를 대행합니다. 애플리케이션은 캐시만 바라봅니다.

> **마켓플레이스 적용**: 현재 적용하지 않음. Cache-Aside로 충분한 상황.

### 5.1 동작 방식

```
Cache-Aside:
  App ──┬── Cache
        └── DB      (App이 둘 다 직접 관리)

Read-Through:
  App ──── Cache ──── DB   (Cache가 DB 접근 대행)
```

### 5.2 코드 예시

만약 **카테고리**에 Read-Through를 적용한다면:

```kotlin
@Configuration
class CacheConfig(
    private val categoryRepository: CategoryJpaRepository
) {
    @Bean
    fun categoryCache(): LoadingCache<String, List<CategoryResponse>> {
        return Caffeine.newBuilder()
            .maximumSize(100)
            .expireAfterWrite(Duration.ofHours(1))
            .build { _ ->
                // Cache Miss 시 자동으로 호출됨
                categoryRepository.findAll()
                    .sortedBy { it.displayOrder }
                    .map { CategoryResponse.from(it) }
            }
    }
}

@Service
class CategoryService(
    private val categoryCache: LoadingCache<String, List<CategoryResponse>>
) {
    fun getAllCategories(): List<CategoryResponse> {
        return categoryCache.get("all")  // Cache Miss면 자동으로 DB 조회
            ?: emptyList()
    }
}
```

> **참고**: 이 프로젝트에서는 `@Cacheable`(Cache-Aside)을 사용했지만,
> 카테고리처럼 거의 안 변하는 데이터는 Read-Through도 좋은 선택입니다.

### 5.3 장단점

| 장점 | 단점 |
|------|------|
| 애플리케이션 코드 단순화 | 캐시 라이브러리 의존성 |
| 일관된 캐시 로직 | 유연성 부족 |
| 캐시 로직 중앙 집중화 | 복잡한 조회 로직 구현 어려움 |

---

## 6. Write-Through

쓰기 시 캐시와 DB에 동시에 저장합니다.

> **마켓플레이스 적용**: 현재 적용하지 않음. `@CacheEvict`(삭제) 방식 사용 중.

### 6.1 동작 방식

```
[쓰기]
Client → App → Cache 저장 + DB 저장 (동기) → 완료

두 저장이 모두 성공해야 완료
```

### 6.2 코드 예시

만약 **상품 생성** 시 Write-Through를 적용한다면:

```kotlin
// Spring @CachePut 사용 - DB 저장 후 캐시도 함께 갱신
@CachePut(value = ["products"], key = "#result.id")
fun createProduct(request: CreateProductRequest): ProductResponse {
    val product = Product.create(request)
    val saved = productRepository.save(product)
    return ProductResponse.from(saved)  // DB 저장 + 캐시 갱신 동시에
}
```

> **현재 프로젝트 방식** (Cache-Aside):
> ```kotlin
> // @CacheEvict - DB 저장 후 캐시 삭제 (다음 조회 시 다시 캐싱)
> @CacheEvict(value = ["popularProducts"], allEntries = true)
> fun updateProduct(...) { ... }
> ```

### 6.3 장단점

| 장점 | 단점 |
|------|------|
| 캐시와 DB 일관성 보장 | 쓰기 지연 증가 (두 번 저장) |
| 읽기 시 항상 최신 데이터 | 사용하지 않을 데이터도 캐싱 |

### 6.4 @CachePut과 트랜잭션 문제 (중요!)

**Q: dirty-check이 있으면 save()가 필요 없지 않나요?**

맞습니다. **UPDATE의 경우** dirty-check으로 자동 반영되므로 `save()` 불필요:

```kotlin
@Transactional
@CachePut(value = ["products"], key = "#id")
fun updateProduct(id: Long, request: UpdateRequest): ProductResponse {
    val product = productRepository.findById(id).orElseThrow()
    product.update(request.name, request.price)  // dirty-check으로 자동 UPDATE
    // save() 불필요!
    return ProductResponse.from(product)
}
```

하지만 **INSERT의 경우** `save()`가 필요합니다 (ID 생성을 위해).

**Q: @CachePut은 정말 "동시에" 저장하나요?**

아닙니다. `@CachePut`은 **트랜잭션 커밋 전에** 캐시에 저장합니다:

```
@Transactional + @CachePut 실행 순서:

1. 트랜잭션 시작
2. 메서드 실행 (DB 저장)
3. 메서드 반환값으로 캐시 저장  ← 여기서 캐시 저장!
4. 트랜잭션 커밋

문제: 3번에서 캐시에 저장되지만, 4번에서 롤백되면?
     → DB에는 없고, 캐시에만 데이터가 존재하는 불일치 발생!
```

**진정한 Write-Through 구현 (트랜잭션 커밋 후 캐시 저장):**

```kotlin
@Service
class ProductService(
    private val productRepository: ProductRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    @Transactional
    fun createProduct(request: CreateProductRequest): ProductResponse {
        val product = productRepository.save(Product.create(request))
        val response = ProductResponse.from(product)

        // 트랜잭션 커밋 후 캐시 저장 이벤트 발행
        eventPublisher.publishEvent(ProductCreatedEvent(response))

        return response
    }
}

@Component
class ProductCacheUpdater(
    private val cacheManager: CacheManager
) {
    // 트랜잭션 커밋 후에만 실행됨!
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onProductCreated(event: ProductCreatedEvent) {
        cacheManager.getCache("products")?.put(event.product.id, event.product)
    }
}
```

### 6.5 왜 @CacheEvict를 더 많이 쓰는가?

| 방식 | 동작 | DB 롤백 시 |
|------|------|-----------|
| `@CacheEvict` | 캐시 삭제 → 다음 조회 시 DB에서 캐싱 | ✅ 안전 (DB 기준으로 다시 캐싱) |
| `@CachePut` | 캐시 즉시 갱신 | ⚠️ 불일치 (DB는 롤백, 캐시는 새 값) |

```
@CachePut + DB 롤백 시나리오:

1. 상품 가격 1000 → 2000 수정 시도
2. @CachePut이 캐시에 2000 저장
3. DB 커밋 중 예외 발생 → DB 롤백 (가격은 여전히 1000)
4. 결과: DB = 1000, 캐시 = 2000 (불일치!)
```

> **결론**: 대부분의 경우 `@CacheEvict`로 삭제하고, 다음 조회 시 다시 캐싱하는 게 더 안전합니다.
> 이것이 마켓플레이스에서 `@CacheEvict`를 사용하는 이유입니다.

---

## 7. Write-Behind (Write-Back)

쓰기를 캐시에만 하고, DB 저장은 비동기로 처리합니다.

> **마켓플레이스 적용**: 현재 적용하지 않음. 조회수 기능 추가 시 적용 가능.

### 7.1 동작 방식

```
[쓰기]
Client → App → Cache 저장 → 즉시 응답
                    │
                    └── (비동기) 배치로 DB 저장
```

### 7.2 코드 예시 (조회수)

```kotlin
@Service
class ProductViewService(
    private val redisTemplate: RedisTemplate<String, String>,
    private val productRepository: ProductRepository
) {
    // 조회 시 Redis에만 기록 (빠름)
    fun incrementViewCount(productId: Long) {
        redisTemplate.opsForValue().increment("viewCount:$productId")
    }

    // 1분마다 DB 동기화
    @Scheduled(fixedRate = 60_000)
    fun syncViewCountsToDB() {
        val keys = redisTemplate.keys("viewCount:*") ?: return

        keys.chunked(100).forEach { batch ->
            val updates = batch.mapNotNull { key ->
                val productId = key.substringAfter("viewCount:").toLongOrNull()
                val count = redisTemplate.opsForValue().getAndDelete(key)?.toLongOrNull() ?: 0
                productId?.let { it to count }
            }
            productRepository.bulkUpdateViewCounts(updates)
        }
    }
}
```

### 7.3 장단점

| 장점 | 단점 |
|------|------|
| 쓰기 성능 극대화 | 데이터 유실 위험 (캐시 장애 시) |
| DB 부하 분산 | 일시적 데이터 불일치 |

> **적합한 상황**: 조회수, 좋아요 등 일시적 유실 허용 가능한 데이터

---

## 8. Refresh-Ahead

TTL 만료 전에 미리 캐시를 갱신합니다.

> **마켓플레이스 적용**: 현재 적용하지 않음. 카테고리나 인기 상품에 적용 시 효과적.

### 8.1 동작 방식

```
[일반 TTL 만료]
────────┬───────────────────
        │
        ▼ Cache Miss → 지연 발생


[Refresh-Ahead]
────────┬───────────────────
        │
        ▼ 백그라운드 갱신 → 지연 없음
   (TTL 80% 시점)
```

### 8.2 코드 예시

만약 **카테고리**에 Refresh-Ahead를 적용한다면:

```kotlin
@Bean
fun categoryCache(): LoadingCache<String, List<CategoryResponse>> {
    return Caffeine.newBuilder()
        .maximumSize(100)
        .expireAfterWrite(Duration.ofHours(1))       // 1시간 후 만료
        .refreshAfterWrite(Duration.ofMinutes(50))  // 50분 후 백그라운드 갱신
        .build { _ ->
            categoryRepository.findAll()
                .sortedBy { it.displayOrder }
                .map { CategoryResponse.from(it) }
        }
}
```

만약 **인기 상품**에 Refresh-Ahead를 적용한다면:

```kotlin
@Bean
fun popularProductsCache(): LoadingCache<String, List<ProductResponse>> {
    return Caffeine.newBuilder()
        .maximumSize(10)
        .expireAfterWrite(Duration.ofMinutes(10))
        .refreshAfterWrite(Duration.ofMinutes(8))  // 8분 후 백그라운드 갱신
        .build { _ ->
            productRepository.findByStatusOrderBySalesCountDesc(
                ProductStatus.ON_SALE,
                PageRequest.of(0, 10)
            ).map { ProductResponse.from(it) }
        }
}
```

> **적합한 상황**: 카테고리, 인기 상품 등 자주 조회되는 Hot Key

---

# Part 3: 캐시 문제와 해결책

## 9. 캐시 무효화 전략

### 9.1 TTL 기반

```kotlin
// 10분 후 자동 만료
redisTemplate.opsForValue().set("key", value, Duration.ofMinutes(10))
```

**적합:** 약간의 불일치 허용, 단순한 구현

### 9.2 명시적 무효화

```kotlin
// 단일 키 삭제
@CacheEvict(value = ["products"], key = "#id")
fun updateProduct(id: Long, request: UpdateRequest)

// 전체 삭제
@CacheEvict(value = ["products"], allEntries = true)
fun bulkUpdateProducts()

// 여러 캐시 동시 무효화
@Caching(evict = [
    CacheEvict(value = ["products"], key = "#id"),
    CacheEvict(value = ["popularProducts"], allEntries = true)
])
fun deleteProduct(id: Long)
```

**allEntries=true vs key 지정:**

| 방식 | 동작 | 적합한 상황 |
|------|------|------------|
| `key = "#id"` | 특정 키 1개 삭제 | 개별 상품 캐시 |
| `allEntries = true` | 해당 캐시의 모든 키 삭제 | 목록/집계 캐시 |

**Q: 상품 수정 시 인기 상품 전체 캐시 삭제는 비효율적이지 않나?**

```kotlin
// 마켓플레이스 코드
@CacheEvict(value = ["popularProducts"], allEntries = true)
fun updateProduct(...) { ... }
```

**A: 현재 구조에서는 비효율적이지 않습니다.**

```
"popularProducts" 캐시 구조:
키: "top10" → [상품1, 상품2, ..., 상품10]  ← 단일 엔트리

allEntries=true로 삭제해도 실제로는 1개만 삭제됨
```

만약 캐시에 수천 개의 키가 있다면 비효율적일 수 있습니다:

```kotlin
// 이런 구조라면 비효율적
"products" 캐시:
키: "1" → 상품1
키: "2" → 상품2
... (수천 개)

@CacheEvict(value = ["products"], allEntries = true)
// → 수천 개 전부 삭제! 이 경우는 key = "#id" 사용
```

**Q: "상품이 TOP 10에 있을 때만 삭제"하면 더 효율적이지 않나?**

```kotlin
// 조건부 삭제 (오버엔지니어링)
fun updateProduct(id: Long, ...) {
    val top10 = cacheManager.getCache("popularProducts")?.get("top10")
    if (top10?.contains(id) == true) {
        cacheManager.getCache("popularProducts")?.evict("top10")
    }
    ...
}
```

**A: 오버엔지니어링입니다.**
- 캐시 조회 비용 추가 (매번 TOP 10을 읽어야 함)
- 로직 복잡도 증가
- 판매량 변경 시 순위가 바뀔 수 있어서 결국 삭제해야 함
- 인기 상품 캐시는 1개뿐이라 삭제 비용이 거의 없음

> **결론**: 목록/집계 캐시는 `allEntries=true`로 단순하게 삭제하는 게 적절합니다.
> 복잡한 조건부 삭제보다 단순하고 안전합니다.

### 9.3 이벤트 기반 무효화

```kotlin
// 이벤트 발행
@Transactional
fun updateProduct(id: Long, request: UpdateRequest): Product {
    val product = productRepository.save(...)
    eventPublisher.publishEvent(ProductUpdatedEvent(id))
    return product
}

// 이벤트 수신하여 연관 캐시 모두 무효화
@EventListener
fun handleProductUpdate(event: ProductUpdatedEvent) {
    cacheManager.getCache("products")?.evict(event.productId)
    cacheManager.getCache("popularProducts")?.clear()
}
```

### 9.4 전략 비교

| 전략 | 일관성 | 복잡도 | 적합한 상황 |
|------|--------|--------|------------|
| TTL | 낮음 | 낮음 | 조회 위주, 불일치 허용 |
| 명시적 무효화 | 높음 | 중간 | 변경 시점 명확 |
| 이벤트 기반 | 높음 | 높음 | MSA, 복잡한 연관관계 |

---

## 10. Cache Stampede (Thundering Herd)

### 10.1 문제

캐시 만료 시 다수의 요청이 동시에 DB 조회

```
TTL 만료 시점
     │
     ├── Request 1 → Cache Miss → DB 조회
     ├── Request 2 → Cache Miss → DB 조회  ← DB 폭주!
     ├── Request 3 → Cache Miss → DB 조회
     └── ...
```

### 10.2 해결책 1: 분산 락

```kotlin
fun getProductWithLock(id: Long): ProductCacheDto {
    val cacheKey = "product:$id"
    val lockKey = "lock:product:$id"

    // 캐시 확인
    redisTemplate.opsForValue().get(cacheKey)?.let { return it }

    // 분산 락 획득
    val acquired = redisTemplate.opsForValue()
        .setIfAbsent(lockKey, "locked", Duration.ofSeconds(5))

    if (acquired == true) {
        try {
            // Double-check
            redisTemplate.opsForValue().get(cacheKey)?.let { return it }

            // 1개만 DB 조회
            val product = productRepository.findById(id).orElseThrow()
            val dto = ProductCacheDto.from(product)
            redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofMinutes(10))
            return dto
        } finally {
            redisTemplate.delete(lockKey)
        }
    } else {
        // 락 획득 실패 → 잠시 대기 후 재시도
        Thread.sleep(50)
        return getProductWithLock(id)
    }
}
```

**분산 락의 핵심: `setIfAbsent` (SETNX)**

```kotlin
val acquired = redisTemplate.opsForValue()
    .setIfAbsent(lockKey, "locked", Duration.ofSeconds(5))
//               ↑         ↑              ↑
//              키        값(아무거나)    TTL(락 타임아웃)
```

Redis 명령어로 보면:
```
SET lock:product:123 "locked" NX EX 5
                              ↑
                              NX = "Not Exists" - 키가 없을 때만 설정
```

**동작 방식:**

```
동시에 100개 요청이 들어왔을 때:

Request 1:   SET lock:product:123 "locked" NX → true  ← 락 획득!
Request 2:   SET lock:product:123 "locked" NX → false ← 이미 키 있음
Request 3:   SET lock:product:123 "locked" NX → false
...
Request 100: false

→ 단 하나(Request 1)만 DB 조회
→ 나머지 99개는 대기 후 재시도 (이때는 캐시에 데이터 있음)
```

**"locked" 값은 중요하지 않음:**

```kotlin
// 다 똑같이 동작함 - 핵심은 "키가 없을 때만 설정"
.setIfAbsent(lockKey, "locked", ...)
.setIfAbsent(lockKey, "1", ...)
.setIfAbsent(lockKey, requestId, ...)  // 디버깅용으로 요청 ID 저장 가능
```

**TTL(5초)의 역할:**

락을 획득한 요청이 죽으면(예외, 서버 다운) 락이 영원히 남을 수 있음.
TTL로 5초 후 자동 해제되어 데드락 방지.

### 10.3 해결책 2: 확률적 조기 갱신

```kotlin
fun getProductWithProbabilisticRefresh(id: Long): ProductCacheDto {
    val cacheKey = "product:$id"

    val cached = redisTemplate.opsForValue().get(cacheKey)
    val ttl = redisTemplate.getExpire(cacheKey, TimeUnit.SECONDS)

    // TTL이 20% 이하로 남으면 10% 확률로 미리 갱신
    if (cached != null && ttl < 120 && Random.nextDouble() < 0.1) {
        CompletableFuture.runAsync { refreshCache(id) }
    }

    return cached ?: fetchAndCache(id)
}
```

---

## 11. Cache Penetration

### 11.1 문제

존재하지 않는 데이터 반복 조회 → 매번 DB 조회

```
Request(id=99999) → Cache Miss → DB (없음) → 반복...
```

### 11.2 해결책: Null 캐싱

```kotlin
fun getProductSafe(id: Long): ProductCacheDto? {
    val cacheKey = "product:$id"

    // EMPTY 마커 체크
    if (redisTemplate.hasKey("$cacheKey:empty") == true) {
        return null
    }

    redisTemplate.opsForValue().get(cacheKey)?.let { return it }

    val product = productRepository.findById(id).orElse(null)

    if (product == null) {
        // 없는 데이터도 짧은 TTL로 캐싱
        redisTemplate.opsForValue().set("$cacheKey:empty", "1", Duration.ofMinutes(1))
        return null
    }

    val dto = ProductCacheDto.from(product)
    redisTemplate.opsForValue().set(cacheKey, dto, Duration.ofMinutes(10))
    return dto
}
```

---

## 12. Cache Avalanche

### 12.1 문제

다수의 캐시가 동시에 만료 → DB 과부하

### 12.2 해결책: TTL Jitter

```kotlin
fun cacheWithJitter(key: String, value: Any, baseTtlMinutes: Long) {
    // 기본 TTL에 ±20% 랜덤 추가
    val jitter = (baseTtlMinutes * 0.2 * Random.nextDouble()).toLong()
    val ttl = baseTtlMinutes + jitter

    redisTemplate.opsForValue().set(key, value, Duration.ofMinutes(ttl))
}

// 예: 기본 10분 → 8~12분 사이로 분산
```

---

## 13. Hot Key 문제

### 13.1 문제

특정 키에 요청 집중 → 단일 Redis 노드 과부하

### 13.2 해결책 1: 로컬 캐시 조합 (다단계)

만약 **인기 상품**에 다단계 캐시를 적용한다면:

```kotlin
// L1: 로컬 캐시 (Caffeine) - 30초 (빠름)
// L2: Redis - 10분 (서버 간 공유)

private val localCache = Caffeine.newBuilder()
    .maximumSize(100)
    .expireAfterWrite(Duration.ofSeconds(30))
    .build<String, List<ProductResponse>>()

fun getPopularProducts(): List<ProductResponse> {
    val cacheKey = "popularProducts:top10"

    // L1 조회 (로컬)
    localCache.getIfPresent(cacheKey)?.let { return it }

    // L2 조회 (Redis)
    val products = redisTemplate.opsForValue().get(cacheKey)
        ?: fetchAndCacheToRedis()

    // L1에 저장
    localCache.put(cacheKey, products)
    return products
}
```

### 13.3 해결책 2: 키 복제

인기 상품처럼 요청이 집중되는 데이터에 적용:

```kotlin
fun getPopularProducts(): List<ProductResponse> {
    val replica = Random.nextInt(3)  // 0, 1, 2
    val cacheKey = "popularProducts:top10:replica:$replica"

    return redisTemplate.opsForValue().get(cacheKey)
        ?: fetchAndCacheAllReplicas()
}

private fun fetchAndCacheAllReplicas(): List<ProductResponse> {
    val products = productRepository.findTop10ByStatusOrderBySalesCountDesc(ProductStatus.ON_SALE)
        .map { ProductResponse.from(it) }

    // 3개 복제본에 모두 저장
    (0..2).forEach { replica ->
        redisTemplate.opsForValue().set(
            "popularProducts:top10:replica:$replica",
            products,
            Duration.ofMinutes(10)
        )
    }
    return products
}
```

---

# Part 4: 운영 및 모니터링

## 14. 로컬 캐시 vs 분산 캐시

### 14.1 비교

| 항목 | 로컬 캐시 (Caffeine) | 분산 캐시 (Redis) |
|------|---------------------|-------------------|
| **속도** | ~0.01ms | ~1ms |
| **용량** | JVM 힙 제한 | 수십 GB 이상 |
| **일관성** | 서버 간 불일치 | 일관성 보장 |
| **장애 영향** | 서버별 독립 | 전체 영향 |

### 14.2 선택 가이드

```
Q1. 여러 서버에서 동일한 데이터가 필요한가?
    YES → 분산 캐시 (Redis)
    NO  → Q2로

Q2. 데이터가 자주 변경되는가?
    YES → 분산 캐시
    NO  → 로컬 캐시 (Caffeine)
```

### 14.3 다단계 캐시 구성 (추천)

```kotlin
@Configuration
class MultiLevelCacheConfig {

    // L1: 로컬 캐시 (Hot 데이터)
    @Bean
    @Primary
    fun caffeineCacheManager(): CacheManager {
        return CaffeineCacheManager().apply {
            setCaffeine(Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(Duration.ofSeconds(30)))
        }
    }

    // L2: Redis 캐시 (전체 데이터)
    @Bean("redisCacheManager")
    fun redisCacheManager(connectionFactory: RedisConnectionFactory): CacheManager {
        return RedisCacheManager.builder(connectionFactory)
            .cacheDefaults(RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(Duration.ofMinutes(10)))
            .build()
    }
}
```

---

## 15. 프로젝트 적용 사례

> 이 프로젝트에서 실제 적용된 캐싱 코드입니다.

### 15.1 카테고리 목록 (Cache-Aside)

카테고리는 거의 변하지 않으므로 캐싱 효과가 큽니다.

```kotlin
// CategoryService.kt - 실제 코드

@Service
class CategoryService(
    private val categoryJpaRepository: CategoryJpaRepository
) {

    // 캐시에서 조회, 없으면 DB 조회 후 캐싱
    @Cacheable(value = ["categories"], key = "'all'")
    fun getAllCategories(): List<CategoryResponse> {
        return categoryJpaRepository.findAll()
            .sortedBy { it.displayOrder }
            .map { CategoryResponse.from(it) }  // Entity → DTO 변환
    }

    // 카테고리 생성 시 캐시 전체 무효화
    @Transactional
    @CacheEvict(value = ["categories"], allEntries = true)
    fun createCategory(req: CreateCategoryRequest): CategoryResponse {
        // ... 생성 로직
    }
}
```

**왜 이렇게 구현했나?**
- `key = "'all'"`: 카테고리는 전체 목록을 한번에 조회하므로 단일 키 사용
- `allEntries = true`: 카테고리 추가/수정 시 전체 캐시 무효화 (개별 무효화보다 단순)
- `CategoryResponse`: Entity가 아닌 DTO를 반환하여 직렬화 문제 방지

### 15.2 인기 상품 목록 (Cache-Aside)

인기 상품은 계산 비용이 높고(정렬), 약간의 지연이 허용됩니다.

```kotlin
// ProductService.kt - 실제 코드

@Service
class ProductService(
    private val productJpaRepository: ProductJpaRepository
) {

    // 인기 상품 TOP 10 캐싱
    @Cacheable(value = ["popularProducts"], key = "'top10'")
    fun getPopularProducts(): List<ProductResponse> {
        return productJpaRepository.findByStatusOrderBySalesCountDesc(
            ProductStatus.ON_SALE,
            PageRequest.of(0, 10)
        ).map { ProductResponse.from(it) }
    }

    // 상품 수정 시 인기 상품 캐시 무효화
    @Transactional
    @CacheEvict(value = ["popularProducts"], allEntries = true)
    fun updateProduct(sellerId: Long, productId: Long, req: UpdateProductRequest): ProductResponse {
        // ... 수정 로직
    }

    // 상품 삭제 시 인기 상품 캐시 무효화
    @Transactional
    @CacheEvict(value = ["popularProducts"], allEntries = true)
    fun deleteProduct(sellerId: Long, productId: Long) {
        // ... 삭제 로직
    }
}
```

**왜 이렇게 구현했나?**
- 상품 수정/삭제 시 판매량 순위가 바뀔 수 있으므로 캐시 무효화
- 주문 완료 시에도 `salesCount`가 증가하므로 무효화 필요 (OrderService에서 처리)

### 15.3 캐시 설정 (CacheConfig)

```kotlin
// CacheConfig.kt - 실제 코드

@Configuration
@EnableCaching
@Profile("local")  // 로컬에서는 Caffeine, Docker/Prod에서는 Redis
class CacheConfig {

    @Bean
    fun cacheManager(): CacheManager {
        return CaffeineCacheManager("popularProducts", "categories").apply {
            setCaffeine(
                Caffeine.newBuilder()
                    .expireAfterWrite(10, TimeUnit.MINUTES)  // TTL 10분
                    .maximumSize(1000)
                    .recordStats()  // 히트율 모니터링
            )
        }
    }
}
```

### 15.4 적용하지 않은 것들

```
[상품 재고]
→ 실시간 정확성 필수, 캐시 대신 원자적 UPDATE 사용
→ ProductJpaRepositoryImpl.decreaseStockAtomically()

[상품 상세]
→ 현재 트래픽 수준에서는 캐싱 불필요
→ 트래픽 증가 시 추가 검토

[검색 결과]
→ 검색 조건 조합이 많아 히트율 낮음
→ 캐싱 효과 미미
```

---

## 16. 모니터링 및 운영

### 16.1 모니터링 구조

```
┌─────────────────┐     ┌─────────────────┐     ┌─────────────────┐
│  Spring Boot    │────▶│   Prometheus    │────▶│    Grafana      │
│  (메트릭 노출)   │     │  (메트릭 수집)   │     │  (시각화/알람)   │
└─────────────────┘     └─────────────────┘     └─────────────────┘
 /actuator/prometheus    prometheus.yml          웹 UI 설정
```

| 구성요소 | 역할 | 설정 위치 |
|---------|------|----------|
| **Spring Boot** | 메트릭 노출 | `application.yml` + micrometer 의존성 |
| **Prometheus** | 메트릭 수집/저장 | `prometheus.yml` (scrape 설정) |
| **Grafana** | 시각화 + 알람 | 웹 UI에서 설정 |
| **Alertmanager** | 알람 전송 (선택) | `alertmanager.yml` (현재 미설정) |

**마켓플레이스 설정 (`prometheus.yml`):**

```yaml
scrape_configs:
  - job_name: 'marketplace-app'
    metrics_path: '/actuator/prometheus'  # Spring Boot 메트릭 엔드포인트
    static_configs:
      - targets: ['app:8080']
    scrape_interval: 10s  # 10초마다 수집
```

### 16.2 필수 모니터링 메트릭

```
캐시 성능:
- cache.hit.rate (히트율) → 목표 90% 이상
- cache.miss.count (미스 횟수)
- cache.eviction.count (제거 횟수)
- cache.size (현재 크기)

Redis:
- redis.memory.used
- redis.connections.active
- redis.commands.duration
```

### 16.3 알람 설정 방법

**방법 1: Grafana에서 설정 (권장, 쉬움)**

```
1. http://localhost:3000 접속 (admin/admin123)
2. 대시보드에서 패널 생성
3. 패널 편집 → Alert 탭 → 조건 설정
4. Slack/이메일 등 알림 채널 연결
```

예시: 캐시 히트율 알람 설정
```
조건: cache_hit_rate < 0.8
평가 주기: 1분
알람 메시지: "캐시 히트율 80% 미만"
```

**방법 2: Prometheus Alertmanager (복잡함)**

`docker-compose.yml`에 Alertmanager 서비스 추가 필요:

```yaml
# docker-compose.yml에 추가
alertmanager:
  image: prom/alertmanager:v0.26.0
  ports:
    - "9093:9093"
  volumes:
    - ./alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml
```

알람 규칙 파일 (`prometheus/alert.rules.yml`):

```yaml
groups:
  - name: cache-alerts
    rules:
      - alert: LowCacheHitRate
        expr: cache_hit_rate < 0.8
        for: 5m  # 5분 동안 지속되면 알람
        labels:
          severity: warning
        annotations:
          summary: "캐시 히트율 80% 미만"

      - alert: RedisHighMemory
        expr: redis_memory_used_bytes / redis_memory_max_bytes > 0.9
        for: 5m
        labels:
          severity: critical
        annotations:
          summary: "Redis 메모리 90% 초과"

      - alert: RedisDown
        expr: redis_up == 0
        for: 1m
        labels:
          severity: critical
        annotations:
          summary: "Redis 연결 실패"
```

> **권장**: 처음에는 Grafana 웹 UI에서 알람 설정하는 게 간단합니다.
> 규모가 커지면 Alertmanager로 전환 검토.

### 16.4 성공 지표

```
도입 전후 비교:
- API 응답 시간: 500ms → 50ms (10배 개선)
- DB CPU: 80% → 30%
- 캐시 히트율: 목표 90% 이상
```

### 16.5 운영 체크리스트

```
□ 캐시 히트율 90% 이상 유지
□ Redis 메모리 사용량 70% 이하 유지
□ TTL 적절성 주기적 검토
□ 캐시 무효화 누락 여부 확인
□ fallback 동작 테스트
```

---

## 요약

### 캐싱 패턴 비교

| 패턴 | 핵심 | 적합한 상황 |
|------|------|------------|
| **Cache-Aside** | 앱이 캐시/DB 직접 관리 | 범용, 읽기 위주 (권장) |
| **Read-Through** | 캐시가 DB 조회 대행 | 일관된 캐시 로직 |
| **Write-Through** | 캐시+DB 동시 저장 | 일관성 중요 |
| **Write-Behind** | 캐시만 저장, DB는 비동기 | 쓰기 성능 중요 |
| **Refresh-Ahead** | TTL 전 미리 갱신 | Hot Key |

### 문제별 해결책

| 문제 | 해결책 |
|------|--------|
| **Cache Stampede** | 분산 락, 확률적 조기 갱신 |
| **Cache Penetration** | Null 캐싱 |
| **Cache Avalanche** | TTL Jitter |
| **Hot Key** | 로컬 캐시 조합, 키 복제 |
| **데이터 불일치** | 짧은 TTL, 지연 삭제 |

### 핵심 원칙

```
┌─────────────────────────────────────────────────────────────┐
│                                                             │
│  1. 섣부른 최적화 금지 - 문제가 생겼을 때 도입               │
│                                                             │
│  2. Entity 캐싱은 안티패턴 - 반드시 DTO로 변환              │
│                                                             │
│  3. 단일 전략 X - 데이터 특성별로 다르게                     │
│                                                             │
│  4. 단순하게 시작 - Cache-Aside + TTL로 시작                │
│                                                             │
│  5. 측정하고 개선 - 히트율 90% 이상 목표                    │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

---

## FAQ (자주 묻는 질문)

### Q1. 캐시 도입 전 무엇을 먼저 해야 하나요?

**A**: 현재 병목을 측정하세요. APM이나 슬로우 쿼리 로그로 원인을 파악한 후, 인덱스/쿼리 최적화로 해결 가능한지 먼저 검토하세요.

---

### Q2. Entity를 캐싱하면 왜 안 되나요?

**A**: 5가지 문제가 있습니다:
1. `LazyInitializationException` 발생
2. Hibernate Proxy 직렬화 문제
3. 불필요한 데이터 노출
4. 캐시 크기 증가
5. 변경 감지 오작동

반드시 DTO로 변환 후 캐싱하세요.

---

### Q3. TTL은 어떻게 설정해야 하나요?

**A**: 데이터 특성에 따라 다릅니다:
- 거의 안 변함 (카테고리): 1시간~1일
- 가끔 변함 (상품 정보): 5~30분
- 자주 변함 (재고): 캐시 안 함 또는 10~30초

불일치 허용 범위를 정의하고 그에 맞게 설정하세요.

---

### Q4. 캐시 무효화가 실패하면 어떻게 되나요?

**A**: DB는 새 값, 캐시는 옛날 값이 됩니다. 해결책:
- TTL을 짧게 설정 (최종 방어선)
- 지연 삭제 (500ms 후 한 번 더)
- 무효화 실패 시 로깅/알람

---

### Q5. 로컬 캐시와 Redis 중 뭘 써야 하나요?

**A**:
- 단일 서버 또는 불일치 허용 → 로컬 캐시 (Caffeine)
- 다중 서버 + 일관성 필요 → Redis
- 최적 성능 → 둘 다 (다단계 캐시)

---

### Q6. Cache Stampede가 뭔가요?

**A**: 캐시 만료 시 수천 개 요청이 동시에 DB 조회하는 현상입니다. 해결책:
- 분산 락으로 1개만 DB 조회
- 확률적 조기 갱신
- Refresh-Ahead

---

### Q7. 캐시 히트율이 낮으면 어떻게 해야 하나요?

**A**: 원인을 파악하세요:
- TTL이 너무 짧음 → 늘리기
- 키 설계가 잘못됨 → 재설계
- 데이터 특성이 캐시에 부적합 → 캐시 제거 검토
- 캐시 크기 부족 → 용량 증가

---

### Q8. 재고 같은 실시간 데이터도 캐싱해야 하나요?

**A**: 아니요. 실시간 정확성이 필요한 데이터는 캐싱하지 마세요:
- 재고 수량
- 결제 상태
- 실시간 가격

원자적 UPDATE로 DB에서 직접 처리하세요.

**마켓플레이스 예시** (`ProductJpaRepositoryImpl.kt`):
```kotlin
// 캐시 대신 원자적 UPDATE 사용
fun decreaseStockAtomically(productId: Long, quantity: Int): Int {
    return entityManager.createQuery("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity
        WHERE p.id = :productId
        AND p.stockQuantity >= :quantity
    """).executeUpdate()
}
```

---

### Q9. 캐시 장애 시 어떻게 대응하나요?

**A**: fallback 전략을 준비하세요:
```kotlin
// 인기 상품 조회 시 Redis 장애 대응
fun getPopularProducts(): List<ProductResponse> {
    return try {
        redisTemplate.opsForValue().get("popularProducts:top10")
            ?: fetchFromDB()
    } catch (e: RedisConnectionException) {
        log.warn("Redis 연결 실패, DB fallback")
        fetchFromDB()  // DB 직접 조회
    }
}
```

> **마켓플레이스**: 로컬 환경에서는 Caffeine(로컬 캐시)을 사용하므로 Redis 장애 영향 없음.
> Docker/Prod 환경에서 Redis 사용 시 위와 같은 fallback 필요.

---

### Q10. 언제 다단계 캐시를 써야 하나요?

**A**: Hot Key가 있고 최고의 성능이 필요할 때:
- L1 (로컬): 자주 접근하는 데이터, 짧은 TTL (10~30초)
- L2 (Redis): 전체 데이터, 긴 TTL (5~10분)

**마켓플레이스 적용 가능 예시**:
- 인기 상품 TOP 10 (메인 페이지에서 매번 조회)
- 카테고리 목록 (모든 페이지에서 사용)

현재는 단일 캐시(Caffeine 또는 Redis)만 사용 중이며, 트래픽 증가 시 다단계 캐시 검토.
