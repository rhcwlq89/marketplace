# 대용량 시스템 학습 가이드

이 프로젝트를 통해 대용량 트래픽을 처리하는 실무급 시스템의 핵심 개념들을 학습할 수 있습니다.

---

## 학습 가이드 목차

| Phase | 주제 | 상세 가이드 |
|-------|------|------------|
| **Phase 1** | 동시성 제어와 재고 관리 | [01_CONCURRENCY_GUIDE.md](./01_CONCURRENCY_GUIDE.md) |
| **Phase 2** | 캐싱 전략 | [02_CACHING_GUIDE.md](./02_CACHING_GUIDE.md) |
| **Phase 3** | 메시지 큐와 이벤트 드리븐 | [03_EVENT_DRIVEN_GUIDE.md](./03_EVENT_DRIVEN_GUIDE.md) |
| **Phase 4** | 장애 대응 패턴 (Resilience) | [04_RESILIENCE_GUIDE.md](./04_RESILIENCE_GUIDE.md) |
| **Phase 5** | 데이터베이스 최적화 | [05_DATABASE_GUIDE.md](./05_DATABASE_GUIDE.md) |
| **Phase 6** | 모니터링과 옵저버빌리티 | [06_MONITORING_GUIDE.md](./06_MONITORING_GUIDE.md) |

### 추가 가이드

- [Docker & Kubernetes 가이드](./DOCKER_K8S_GUIDE.md)

---

## 학습 로드맵

```
Week 1-2: 동시성 문제 이해
    └── 재고 감소 문제, Race Condition, Check-then-Act
    └── 원자적 UPDATE로 과잉 판매 방지 (핵심!)
    └── 멱등성 키로 중복 요청 방지

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
    └── Fallback 전략과 Graceful Degradation

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

## 각 Phase 핵심 요약

### Phase 1: 동시성 제어

**핵심**: 재고 감소 시 원자적 UPDATE 사용

```sql
UPDATE product
SET stock_quantity = stock_quantity - :quantity
WHERE id = :productId AND stock_quantity >= :quantity
```

- DB Row Lock이 동시성 자동 처리
- 분산 락 없이도 과잉 판매 방지
- [상세 가이드 →](./01_CONCURRENCY_GUIDE.md)

---

### Phase 2: 캐싱 전략

**핵심**: 데이터 특성에 따른 캐싱 전략 선택

| 데이터 특성 | 권장 전략 | TTL |
|------------|----------|-----|
| 거의 안 변함 | Read-Through + Refresh-Ahead | 1시간~1일 |
| 가끔 변함 | Cache-Aside + 명시적 무효화 | 5~30분 |
| 자주 변함 | 캐시 안 함 | - |

- Entity 캐싱은 안티패턴, DTO로 변환 후 캐싱
- [상세 가이드 →](./02_CACHING_GUIDE.md)

---

### Phase 3: 이벤트 드리븐

**핵심**: Outbox 패턴으로 메시지 발행 신뢰성 보장

```
┌─────────────────────────────────────────┐
│     같은 DB 트랜잭션                      │
│  orders 테이블 + outbox_events 테이블    │
│         둘 다 성공하거나 둘 다 실패        │
└─────────────────────────────────────────┘
              ↓ 별도 스케줄러
         Kafka로 발행
```

- At-least-once 보장, Consumer 멱등성 필요
- [상세 가이드 →](./03_EVENT_DRIVEN_GUIDE.md)

---

### Phase 4: Resilience 패턴

**핵심**: Circuit Breaker로 장애 전파 방지

```
CLOSED (정상) → 실패율 초과 → OPEN (차단)
                                ↓ 대기 후
                          HALF-OPEN (테스트)
                           ↓ 성공 시
                          CLOSED (복구)
```

- Rate Limiter: 초당 요청 수 제한
- Bulkhead: 리소스 격리
- [상세 가이드 →](./04_RESILIENCE_GUIDE.md)

---

### Phase 5: 데이터베이스 최적화

**핵심**: 인덱스와 커서 기반 페이지네이션

```sql
-- 복합 인덱스: 등호 조건을 앞에, 범위 조건을 뒤에
CREATE INDEX idx_products_status_created_at
ON products(status, created_at DESC);

-- 커서 기반 페이지네이션
SELECT * FROM products
WHERE (created_at, id) < (:cursor_time, :cursor_id)
ORDER BY created_at DESC, id DESC
LIMIT 20;
```

- Read Replica로 읽기/쓰기 분리
- [상세 가이드 →](./05_DATABASE_GUIDE.md)

---

### Phase 6: 모니터링

**핵심**: RED Method로 서비스 상태 파악

| 메트릭 | 의미 |
|--------|------|
| **R**ate | 초당 요청 수 |
| **E**rrors | 에러 발생률 |
| **D**uration | 응답 시간 (p50, p95, p99) |

- Prometheus + Grafana 조합
- 커스텀 비즈니스 메트릭 추가
- [상세 가이드 →](./06_MONITORING_GUIDE.md)

---

## 면접 대비 핵심 질문

### 동시성
1. 동시에 같은 상품에 100명이 주문하면? → 원자적 UPDATE
2. 분산 락 vs 원자적 UPDATE 차이점?

### 캐싱
3. 캐시 무효화 전략에는 어떤 것들이 있나요?
4. 캐시 스탬피드를 어떻게 방지하나요?

### 메시지 큐
5. Kafka의 exactly-once 전달을 어떻게 보장하나요?
6. Outbox 패턴은 왜 필요하고 어떻게 동작하나요?

### 장애 대응
7. Circuit Breaker의 상태 전이를 설명해주세요.
8. Fallback 전략은 무엇이고 언제 사용하나요?

### 데이터베이스
9. 커서 기반 페이지네이션의 장점은?
10. N+1 문제를 어떻게 해결하나요?

### 모니터링
11. p99 응답시간이 중요한 이유는?
12. 어떤 메트릭을 모니터링해야 하나요?

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

---

## 마무리

이 프로젝트를 통해 학습한 내용을 실제 업무에 적용할 때 기억할 점:

1. **모든 기술에는 트레이드오프가 있다** - 복잡성 vs 필요성을 항상 고려
2. **측정 없이 최적화 없다** - 병목 지점을 먼저 파악하고 개선
3. **장애는 반드시 발생한다** - 장애 상황을 가정하고 설계
4. **점진적으로 개선하라** - 한 번에 모든 것을 바꾸려 하지 말 것
