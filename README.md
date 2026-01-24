# 온라인 마켓플레이스 백엔드 API

온라인 마켓플레이스의 백엔드 API입니다. 판매자는 상품을 등록하고, 구매자는 상품을 검색하여 주문할 수 있습니다.

## 기술 스택

- **Language**: Kotlin 1.9
- **Framework**: Spring Boot 3.2
- **Database**: H2 (로컬), MySQL 8.0 (Docker/Prod)
- **Cache**: Caffeine (로컬), Redis (Docker/Prod)
- **Message Queue**: Kafka (Docker/Prod)
- **Distributed Lock**: Redisson
- **Resilience**: Resilience4j (CircuitBreaker, RateLimiter, Bulkhead, Retry)
- **Monitoring**: Prometheus + Grafana, Micrometer
- **ORM**: Spring Data JPA + QueryDSL
- **Security**: Spring Security + JWT
- **Documentation**: SpringDoc OpenAPI (Swagger)
- **Build**: Gradle 8.5
- **Container**: Docker & Docker Compose

### 기술 선택 이유

- **Kotlin**: Java보다 간결한 문법, null-safety, data class 등 생산성 향상
- **Spring Boot 3.2**: 최신 LTS 버전으로 안정성과 성능 보장
- **멀티 모듈 구조**: 의존성 역전 원칙(DIP) 적용, 모듈 간 명확한 책임 분리
- **Redis/Kafka**: 대용량 트래픽 처리를 위한 분산 캐시 및 메시지 큐
- **Resilience4j**: 서킷 브레이커, Rate Limiting 등 장애 대응 패턴

## 프로젝트 구조

```
marketplace/
├── marketplace-common/     # 공통 예외, 에러코드, 유틸리티
├── marketplace-domain/     # Entity, Repository 인터페이스, 비즈니스 로직
├── marketplace-infra/      # JPA Repository 구현체, 외부 연동
└── marketplace-api/        # Controller, Security, 설정, 실행
```

### 모듈 의존성

```
api → domain ← infra
      ↑
    common
```

- `domain` 모듈은 `infra`를 의존하지 않음 (의존성 역전)
- `common`은 모든 모듈에서 사용 가능

## 실행 방법

### 1. 로컬 실행 (H2 + Caffeine)

가장 간단한 실행 방법입니다. Redis, Kafka 없이 인메모리로 동작합니다.

```bash
# 빌드
./gradlew build -x test

# 실행
./gradlew :marketplace-api:bootRun --args='--spring.profiles.active=local'
```

**특징:**
- H2 인메모리 데이터베이스 사용
- Caffeine 로컬 캐시 사용
- 분산 락 비활성화 (NoOp)
- Kafka/Outbox 비활성화

---

### 2. Docker Compose 실행 (전체 인프라)

Redis, Kafka, Prometheus, Grafana 등 전체 인프라와 함께 실행합니다.

```bash
# 전체 서비스 실행
docker-compose up -d

# 로그 확인
docker-compose logs -f app

# 개별 서비스 로그
docker-compose logs -f redis
docker-compose logs -f kafka

# 종료
docker-compose down

# 볼륨까지 삭제하고 종료
docker-compose down -v
```

**포트 정보:**
| 서비스 | 포트 | 설명 |
|--------|------|------|
| App | 8080 | 마켓플레이스 API |
| MySQL | 3306 | 데이터베이스 |
| Redis | 6379 | 분산 캐시 |
| Kafka | 9092 | 메시지 큐 |
| Zookeeper | 2181 | Kafka 코디네이터 |
| Prometheus | 9090 | 메트릭 수집 |
| Grafana | 3000 | 대시보드 |

---

### 3. 부분 인프라 실행 (개발용)

애플리케이션은 로컬에서 실행하고, 인프라만 Docker로 실행합니다.

```bash
# 인프라만 실행 (MySQL, Redis, Kafka)
docker-compose up -d mysql redis zookeeper kafka

# 인프라 상태 확인
docker-compose ps

# 로컬에서 앱 실행 (docker 프로파일)
./gradlew :marketplace-api:bootRun --args='--spring.profiles.active=docker'
```

---

### 4. 모니터링 대시보드

```bash
# Prometheus UI
open http://localhost:9090

# Grafana 대시보드 (admin / admin123)
open http://localhost:3000

# 애플리케이션 메트릭 확인
curl http://localhost:8080/actuator/prometheus

# 헬스체크
curl http://localhost:8080/actuator/health
```

## API 문서

### Swagger UI
- 로컬: http://localhost:8080/swagger-ui.html
- Docker: http://localhost:8080/swagger-ui.html

### H2 Console (로컬 전용)
- URL: http://localhost:8080/h2-console
- JDBC URL: `jdbc:h2:mem:marketplace`
- Username: `sa`
- Password: (비워두기)

## 테스트 계정

| Role   | Email               | Password    |
|--------|---------------------|-------------|
| ADMIN  | admin@example.com   | admin123!   |
| SELLER | seller@example.com  | seller123!  |
| BUYER  | buyer@example.com   | buyer123!   |

## API 명세

### 인증 API

| Method | URI                    | Description | 인증 |
|--------|------------------------|-------------|------|
| POST   | `/api/v1/auth/signup`  | 회원가입     | X    |
| POST   | `/api/v1/auth/login`   | 로그인       | X    |
| POST   | `/api/v1/auth/refresh` | 토큰 갱신    | X    |

### 회원 API

| Method | URI                    | Description         | 인증   |
|--------|------------------------|---------------------|--------|
| GET    | `/api/v1/members/me`   | 내 정보 조회         | O      |
| PATCH  | `/api/v1/members/me`   | 내 정보 수정         | O      |
| GET    | `/api/v1/admin/members`| 회원 목록 (관리자)   | ADMIN  |

### 상품 API

| Method | URI                              | Description         | 인증          |
|--------|----------------------------------|---------------------|---------------|
| POST   | `/api/v1/products`               | 상품 등록           | SELLER        |
| GET    | `/api/v1/products`               | 상품 목록 조회       | X             |
| GET    | `/api/v1/products/{id}`          | 상품 상세 조회       | X             |
| PATCH  | `/api/v1/products/{id}`          | 상품 수정           | SELLER (본인) |
| DELETE | `/api/v1/products/{id}`          | 상품 삭제           | SELLER (본인) |
| POST   | `/api/v1/products/{id}/images`   | 이미지 업로드       | SELLER (본인) |
| GET    | `/api/v1/products/popular`       | 인기 상품 목록       | X             |

### 주문 API

| Method | URI                                    | Description       | 인증          |
|--------|----------------------------------------|-------------------|---------------|
| POST   | `/api/v1/orders`                       | 주문 생성         | BUYER         |
| GET    | `/api/v1/orders`                       | 내 주문 목록       | O             |
| GET    | `/api/v1/orders/{id}`                  | 주문 상세 조회     | O (본인)      |
| POST   | `/api/v1/orders/{id}/cancel`           | 주문 취소         | BUYER (본인)  |
| GET    | `/api/v1/sellers/orders`               | 판매자 주문 목록   | SELLER        |
| PATCH  | `/api/v1/sellers/orders/{id}/status`   | 배송 상태 변경     | SELLER        |

### 카테고리 API

| Method | URI                        | Description      | 인증   |
|--------|----------------------------|------------------|--------|
| GET    | `/api/v1/categories`       | 카테고리 목록     | X      |
| POST   | `/api/v1/admin/categories` | 카테고리 등록     | ADMIN  |

## 주요 기능

### 핵심 기능
- [x] JWT 기반 인증 (Access Token 1시간, Refresh Token 7일)
- [x] JWT 블랙리스트 (로그아웃 시 토큰 무효화)
- [x] 역할 기반 접근 제어 (BUYER, SELLER, ADMIN)
- [x] 상품 CRUD 및 이미지 업로드
- [x] 상품 검색/필터링 (키워드, 카테고리, 가격대, 상태)
- [x] 커서 기반 페이지네이션 (대용량 데이터 효율적 조회)
- [x] 주문 생성 및 취소
- [x] 주문 상태 관리 (PENDING → CONFIRMED → SHIPPED → DELIVERED)

### 대용량 처리
- [x] **Redis 분산 캐시**: Caffeine(로컬) → Redis(분산) 전환
- [x] **Redisson 분산 락**: 재고 관리 동시성 문제 해결
- [x] **원자적 재고 업데이트**: Check-then-act 취약점 제거
- [x] **Kafka 메시지 큐**: 비동기 이벤트 처리
- [x] **Outbox 패턴**: 이벤트 발행 신뢰성 보장

### Resilience 패턴
- [x] **Circuit Breaker**: 장애 전파 방지
- [x] **Rate Limiting**: API 요청 제한
- [x] **Bulkhead**: 리소스 격리
- [x] **Retry**: 일시적 장애 복구

### 모니터링
- [x] **Prometheus 메트릭**: 시스템/비즈니스 메트릭 수집
- [x] **Grafana 대시보드**: 시각화
- [x] **커스텀 비즈니스 메트릭**: 주문 수, 실패율 등
- [x] **헬스체크**: Redis, Kafka 상태 모니터링

### 데이터베이스 최적화
- [x] **인덱스 최적화**: products, orders, order_items
- [x] **Read Replica 지원**: 읽기/쓰기 분리 (prod)
- [x] **HikariCP 튜닝**: Connection Pool 최적화

### 기타
- [x] **멀티 모듈 구조**: api/domain/infra/common 분리
- [x] **Graceful Shutdown**: 정상적인 종료 처리
- [x] **요청 로깅**: Request ID 기반 추적
- [x] **Swagger API 문서화**
- [x] **Docker Compose**: 전체 인프라 구성

## 테스트

### 단위 테스트

```bash
# 전체 테스트 실행
./gradlew test

# 특정 모듈 테스트
./gradlew :marketplace-api:test
```

---

## 로컬 테스트 시나리오

### 시나리오 1: 기본 API 테스트

#### Step 1. 서버 실행
```bash
./gradlew :marketplace-api:bootRun --args='--spring.profiles.active=local'
```

#### Step 2. 로그인
```bash
# 구매자 로그인
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"buyer@example.com","password":"buyer123!"}'

# 응답에서 accessToken 복사
```

#### Step 3. 상품 목록 조회
```bash
curl http://localhost:8080/api/v1/products
```

#### Step 4. 상품 상세 조회
```bash
curl http://localhost:8080/api/v1/products/1
```

#### Step 5. 주문 생성
```bash
# ACCESS_TOKEN을 Step 2에서 받은 토큰으로 교체
curl -X POST http://localhost:8080/api/v1/orders \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ACCESS_TOKEN" \
  -d '{
    "orderItems": [{"productId": 1, "quantity": 2}],
    "shippingAddress": {
      "zipCode": "12345",
      "address": "서울시 강남구",
      "addressDetail": "101호",
      "receiverName": "홍길동",
      "receiverPhone": "010-1234-5678"
    }
  }'
```

#### Step 6. 내 주문 목록 조회
```bash
curl http://localhost:8080/api/v1/orders \
  -H "Authorization: Bearer ACCESS_TOKEN"
```

---

### 시나리오 2: 판매자 기능 테스트

#### Step 1. 판매자 로그인
```bash
curl -X POST http://localhost:8080/api/v1/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"seller@example.com","password":"seller123!"}'
```

#### Step 2. 상품 등록
```bash
curl -X POST http://localhost:8080/api/v1/products \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SELLER_TOKEN" \
  -d '{
    "name": "테스트 상품",
    "description": "테스트 상품 설명",
    "price": 50000,
    "stockQuantity": 100,
    "categoryId": 1,
    "status": "ON_SALE"
  }'
```

#### Step 3. 판매자 주문 목록 조회
```bash
curl http://localhost:8080/api/v1/sellers/orders \
  -H "Authorization: Bearer SELLER_TOKEN"
```

#### Step 4. 주문 상태 변경
```bash
curl -X PATCH http://localhost:8080/api/v1/sellers/orders/1/status \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer SELLER_TOKEN" \
  -d '{"status": "CONFIRMED"}'
```

---

### 시나리오 3: 커서 기반 페이지네이션 테스트

```bash
# 첫 페이지 조회
curl "http://localhost:8080/api/v1/products/cursor?limit=5"

# 다음 페이지 조회 (nextCursor 값 사용)
curl "http://localhost:8080/api/v1/products/cursor?limit=5&cursor=CURSOR_VALUE"
```

---

### 시나리오 4: 로그아웃 테스트

```bash
curl -X POST http://localhost:8080/api/v1/auth/logout \
  -H "Content-Type: application/json" \
  -d '{"accessToken":"ACCESS_TOKEN","refreshToken":"REFRESH_TOKEN"}'
```

---

### 시나리오 5: 동시성 테스트 (Docker 환경)

여러 터미널에서 동시에 같은 상품 주문을 요청하여 재고 관리 테스트:

```bash
# Terminal 1, 2, 3에서 동시 실행
for i in {1..10}; do
  curl -X POST http://localhost:8080/api/v1/orders \
    -H "Content-Type: application/json" \
    -H "Authorization: Bearer ACCESS_TOKEN" \
    -d '{
      "orderItems": [{"productId": 1, "quantity": 1}],
      "shippingAddress": {
        "zipCode": "12345",
        "address": "서울시",
        "addressDetail": "상세주소",
        "receiverName": "테스터",
        "receiverPhone": "010-0000-0000"
      }
    }' &
done
wait
```

---

### 시나리오 6: 인기 상품 캐싱 테스트

```bash
# 첫 번째 호출 (캐시 미스)
time curl http://localhost:8080/api/v1/products/popular

# 두 번째 호출 (캐시 히트 - 더 빠름)
time curl http://localhost:8080/api/v1/products/popular
```

---

## k6 부하 테스트 (선택)

k6가 설치되어 있다면 부하 테스트를 실행할 수 있습니다.

```bash
# k6 설치 (macOS)
brew install k6

# 부하 테스트 스크립트 생성
cat > load-test.js << 'EOF'
import http from 'k6/http';
import { check, sleep } from 'k6';

export const options = {
  stages: [
    { duration: '30s', target: 20 },
    { duration: '1m', target: 50 },
    { duration: '30s', target: 0 },
  ],
};

export default function () {
  const res = http.get('http://localhost:8080/api/v1/products');
  check(res, { 'status was 200': (r) => r.status == 200 });
  sleep(1);
}
EOF

# 부하 테스트 실행
k6 run load-test.js
```

## 환경 변수 (Docker)

| 변수명                    | 기본값                | 설명                    |
|---------------------------|----------------------|-------------------------|
| MYSQL_HOST                | mysql                | MySQL 호스트             |
| MYSQL_PORT                | 3306                 | MySQL 포트               |
| MYSQL_DATABASE            | marketplace          | 데이터베이스 이름         |
| MYSQL_USER                | marketplace          | 데이터베이스 사용자       |
| MYSQL_PASSWORD            | marketplace123       | 데이터베이스 비밀번호     |
| REDIS_HOST                | redis                | Redis 호스트             |
| REDIS_PORT                | 6379                 | Redis 포트               |
| KAFKA_BOOTSTRAP_SERVERS   | kafka:29092          | Kafka 브로커 주소        |
| JWT_SECRET                | (기본값 있음)         | JWT 서명 키 (32자 이상)   |

## 추가 구현 사항

### 동시성 처리 (분산 락 + 원자적 업데이트)

```kotlin
// 분산 락 어노테이션 적용
@DistributedLock(key = "'order:create:' + #buyerId", waitTime = 5, leaseTime = 30)
fun createOrder(buyerId: Long, req: CreateOrderRequest): OrderResponse

// 원자적 재고 감소 (UPDATE ... WHERE 조건)
fun decreaseStockAtomically(productId: Long, quantity: Int): Int {
    return entityManager.createQuery("""
        UPDATE Product p
        SET p.stockQuantity = p.stockQuantity - :quantity
        WHERE p.id = :productId
        AND p.stockQuantity >= :quantity
    """).executeUpdate()
}
```

### 요청 추적
모든 요청에 고유한 Request ID를 부여하여 로그 추적이 가능합니다.

```
[2024-01-20 10:30:15] [INFO] [a1b2c3d4] [RequestLoggingFilter] GET /api/v1/products started
[2024-01-20 10:30:15] [INFO] [a1b2c3d4] [RequestLoggingFilter] GET /api/v1/products completed in 45ms with status 200
```

### 에러 응답 형식
일관된 에러 응답 형식을 제공합니다.

```json
{
  "timestamp": "2024-01-20T10:30:15",
  "status": 400,
  "error": "EMAIL_ALREADY_EXISTS",
  "message": "Email already exists",
  "path": "/api/v1/auth/signup"
}
```

---

## 트러블슈팅

### Redis 연결 오류
```bash
# Redis 상태 확인
docker-compose ps redis
docker-compose logs redis

# Redis CLI로 연결 테스트
docker exec -it marketplace-redis redis-cli ping
```

### Kafka 연결 오류
```bash
# Kafka 상태 확인
docker-compose ps kafka zookeeper
docker-compose logs kafka

# 토픽 목록 확인
docker exec -it marketplace-kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### 데이터베이스 연결 오류
```bash
# MySQL 상태 확인
docker-compose ps mysql
docker-compose logs mysql

# MySQL 접속 테스트
docker exec -it marketplace-mysql mysql -u marketplace -pmarketplace123 marketplace
```

### 포트 충돌
```bash
# 사용 중인 포트 확인
lsof -i :8080
lsof -i :3306
lsof -i :6379
lsof -i :9092

# 기존 컨테이너 정리
docker-compose down
docker system prune -f
```

### 메모리 부족
```bash
# Docker 리소스 확인
docker stats

# 불필요한 컨테이너/이미지 정리
docker system prune -a
```

---

## 아키텍처

```
┌─────────────────────────────────────────────────────────────────┐
│                         Client                                   │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    Rate Limiting Filter                          │
│                    (Resilience4j RateLimiter)                    │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                    JWT Authentication Filter                     │
│                    (+ Token Blacklist Check)                     │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                         Controllers                              │
│        (Product, Order, Auth, Category, Member)                  │
└─────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                          Services                                │
│    @CircuitBreaker  @Bulkhead  @Retry  @DistributedLock         │
└─────────────────────────────────────────────────────────────────┘
                    │                       │
          ┌─────────┴─────────┐    ┌───────┴───────┐
          ▼                   ▼    ▼               ▼
┌──────────────────┐  ┌───────────────┐  ┌──────────────────┐
│   MySQL (Write)  │  │  Redis Cache  │  │  Kafka (Events)  │
│   MySQL (Read)   │  │  Redis Lock   │  │  Outbox Pattern  │
└──────────────────┘  └───────────────┘  └──────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                        Monitoring                                │
│              Prometheus  ───►  Grafana                          │
│              (Metrics)        (Dashboard)                        │
└─────────────────────────────────────────────────────────────────┘
```
