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

### 3. Kubernetes (k3d) 실행

k3d 클러스터에서 전체 인프라를 실행합니다.

#### 사전 요구사항
- Docker
- k3d (`brew install k3d`)
- kubectl (`brew install kubectl`)

#### 배포 실행
```bash
# 배포 스크립트 실행 (클러스터 생성 + 빌드 + 배포)
./k8s/deploy.sh

# Pod 상태 확인
kubectl get pods -n marketplace

# 로그 확인
kubectl logs -f deployment/marketplace-app -n marketplace
```

#### 접속 정보
| 서비스 | URL | 설명 |
|--------|-----|------|
| App | http://localhost:8080 | 마켓플레이스 API |
| Prometheus | http://localhost:9090 | 메트릭 수집 |
| Grafana | http://localhost:3000 | 대시보드 (admin/admin123) |

#### 클러스터 관리
```bash
# 클러스터 중지
k3d cluster stop marketplace

# 클러스터 시작
k3d cluster start marketplace

# 클러스터 삭제
k3d cluster delete marketplace
```

---

### 4. 부분 인프라 실행 (개발용)

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

### 5. 모니터링 대시보드

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

---

## 프로덕션 환경 전환 시 보완 사항

> 현재 프로젝트는 학습/데모 목적으로 제작되었습니다. 실제 운영 쇼핑몰로 전환하려면 아래 사항들을 보완해야 합니다.

### 1. 기능적으로 추가되어야 하는 부분

#### 결제 시스템
- [ ] **PG(Payment Gateway) 연동**: 토스페이먼츠, 카카오페이, 네이버페이 등 실제 결제 수단 연동
- [ ] **결제 상태 관리**: 결제 대기/완료/취소/환불 상태 처리
- [ ] **가상계좌/계좌이체 지원**: 무통장입금 등 다양한 결제 수단
- [ ] **정기결제/구독 결제**: 정기배송 상품을 위한 구독 결제 시스템
- [ ] **결제 실패 재시도 로직**: 네트워크 오류 등으로 인한 결제 실패 복구

#### 장바구니 및 위시리스트
- [ ] **장바구니 기능 완성**: 현재 개발 중인 장바구니 기능 완료
- [ ] **비회원 장바구니**: 쿠키/세션 기반 비회원 장바구니
- [ ] **장바구니 ↔ 위시리스트 연동**: 나중에 구매할 상품 저장

#### 쿠폰 및 할인
- [ ] **쿠폰 시스템**: 정액/정률/무료배송 쿠폰
- [ ] **적립금/포인트**: 구매 적립 및 사용
- [ ] **프로모션 가격**: 기간 한정 할인, 묶음 할인
- [ ] **첫 구매 할인, 회원등급별 할인**

#### 배송 관리
- [ ] **배송 추적 연동**: 택배사 API 연동 (CJ대한통운, 롯데택배 등)
- [ ] **배송비 정책**: 조건부 무료배송, 지역별 배송비
- [ ] **반품/교환 프로세스**: RMA(Return Merchandise Authorization) 시스템
- [ ] **새벽배송/당일배송 옵션**

#### 상품 관리 고도화
- [ ] **옵션/변형 상품**: 색상, 사이즈 등 다양한 옵션 조합
- [ ] **재고 관리 고도화**: 입고/출고/재고실사 관리
- [ ] **예약주문**: 출시 전 상품 예약
- [ ] **묶음상품/세트상품**

#### 리뷰 및 평점
- [ ] **상품 리뷰 시스템**: 텍스트 + 이미지 + 동영상 리뷰
- [ ] **리뷰 신뢰도**: 구매 인증 리뷰, 도움이 됐어요 기능
- [ ] **판매자 평점 및 답변**

#### 고객 서비스
- [ ] **1:1 문의 시스템**: 문의 등록/답변/알림
- [ ] **FAQ 관리**
- [ ] **실시간 채팅 상담**: WebSocket 기반 채팅
- [ ] **분쟁 조정 시스템**: 구매자-판매자 간 분쟁 처리

#### 알림
- [ ] **이메일 발송**: 주문확인, 배송알림, 마케팅 메일
- [ ] **SMS/카카오 알림톡**: 주요 이벤트 알림
- [ ] **푸시 알림**: 앱 푸시 (FCM 연동)

#### 검색 고도화
- [ ] **Elasticsearch 연동**: 전문 검색, 자동완성, 오타 교정
- [ ] **검색어 추천 및 인기 검색어**
- [ ] **필터링 고도화**: 다중 필터, 패싯 검색

---

### 2. 서비스적으로 추가되어야 하는 부분

#### 보안 강화
- [ ] **2단계 인증(2FA)**: OTP, SMS 인증
- [ ] **비밀번호 정책 강화**: 복잡도, 주기적 변경, 이전 비밀번호 재사용 금지
- [ ] **로그인 이력 관리**: 의심스러운 로그인 시 알림
- [ ] **IP 기반 접근 제한**: 관리자 페이지 IP 화이트리스트
- [ ] **민감정보 암호화**: 개인정보 DB 암호화 (AES-256)
- [ ] **PCI-DSS 준수**: 결제 정보 보안 표준

#### 법적 요구사항
- [ ] **개인정보 처리방침** 및 동의 관리
- [ ] **이용약관** 동의 및 버전 관리
- [ ] **전자상거래법 준수**: 청약철회, 구매안전서비스 등
- [ ] **통신판매업 신고**: 사업자 정보 표시
- [ ] **GDPR/CCPA 대응**: 해외 사용자 대상 시

#### 판매자 관리
- [ ] **판매자 심사 프로세스**: 사업자등록증, 통장사본 검증
- [ ] **정산 시스템**: 판매대금 정산, 수수료 계산
- [ ] **판매자 대시보드**: 매출 통계, 정산 내역
- [ ] **판매자 등급 시스템**

#### 운영 도구
- [ ] **백오피스(Admin) 시스템**: 주문/상품/회원/정산 관리 UI
- [ ] **운영자 권한 관리**: 세분화된 권한 (주문조회만/수정가능 등)
- [ ] **감사 로그(Audit Log)**: 관리자 활동 기록
- [ ] **통계 대시보드**: 매출/방문자/전환율 리포트

#### 고객 경험
- [ ] **회원 등급 시스템**: VIP, 골드, 실버 등 등급별 혜택
- [ ] **생일 쿠폰, 휴면 고객 리텐션**
- [ ] **최근 본 상품, 추천 상품**
- [ ] **A/B 테스트 시스템**

---

### 3. 코드적으로 변경되어야 하는 부분

#### 테스트 커버리지
- [ ] **테스트 커버리지 확대**: 현재 5개 → 도메인/서비스 전체 커버
- [ ] **통합 테스트 추가**: Controller 통합 테스트
- [ ] **E2E 테스트**: 실제 시나리오 기반 테스트
- [ ] **성능 테스트 자동화**: k6/Gatling CI/CD 연동

#### 코드 품질
- [ ] **정적 분석 도구**: detekt, ktlint CI 연동
- [ ] **코드 리뷰 필수화**: PR 규칙
- [ ] **API 버저닝 전략**: `/api/v1`, `/api/v2` 공존 전략
- [ ] **Deprecated API 처리 정책**

#### 예외 처리 강화
- [ ] **비즈니스 예외 세분화**: 더 상세한 에러 코드
- [ ] **글로벌 예외 핸들러 고도화**: 예상치 못한 예외 처리
- [ ] **재시도 가능 여부 표시**: 클라이언트에게 재시도 가이드

#### 트랜잭션 관리
- [ ] **분산 트랜잭션 처리**: Saga 패턴 적용 (주문-결제-재고)
- [ ] **보상 트랜잭션**: 실패 시 롤백 로직
- [ ] **멱등성(Idempotency) 보장**: 결제 중복 방지

#### 데이터 관리
- [ ] **소프트 삭제(Soft Delete)**: 회원/상품 삭제 시 데이터 보존
- [ ] **데이터 보관 정책**: 오래된 데이터 아카이빙
- [ ] **개인정보 마스킹**: 로그, 응답에서 개인정보 마스킹

#### 보안 코드
```kotlin
// 현재: JWT Secret이 application.yml에 평문
jwt:
  secret: "your-secret-key..."

// 개선: 외부 Secret Manager 사용 (AWS Secrets Manager, Vault 등)
jwt:
  secret: ${JWT_SECRET}  // 환경변수로 주입
```

---

### 4. 인프라/프로젝트 규모 보완 사항

#### 데이터베이스
- [ ] **Master-Slave 복제**: 읽기 성능 향상 (현재 설정만 존재)
- [ ] **데이터베이스 샤딩**: 대용량 데이터 분산
- [ ] **자동 백업 및 복구**: Point-in-time Recovery
- [ ] **데이터베이스 마이그레이션**: Flyway/Liquibase 도입

#### 캐시
- [ ] **Redis Cluster**: 단일 Redis → 클러스터 (고가용성)
- [ ] **Redis Sentinel**: 자동 장애 조치
- [ ] **캐시 워밍업 전략**: 서버 재시작 시 캐시 미리 로드

#### 메시지 큐
- [ ] **Kafka Cluster**: 최소 3개 브로커 구성
- [ ] **Dead Letter Queue**: 처리 실패 메시지 관리
- [ ] **메시지 재처리 전략**: 실패 메시지 복구 프로세스

#### 컨테이너/오케스트레이션
- [ ] **프로덕션 K8s 클러스터**: EKS, GKE, AKS 등 관리형 서비스
- [ ] **Auto Scaling**: HPA(Horizontal Pod Autoscaler) 설정
- [ ] **Resource Limits**: CPU/메모리 제한 및 요청량 설정
- [ ] **Pod Disruption Budget**: 무중단 배포 보장
- [ ] **Istio/Linkerd**: 서비스 메시 (트래픽 관리, mTLS)

#### CI/CD
- [ ] **CI/CD 파이프라인**: GitHub Actions, GitLab CI, Jenkins
- [ ] **Blue-Green / Canary 배포**: 무중단 배포 전략
- [ ] **자동화된 롤백**: 배포 실패 시 자동 롤백
- [ ] **환경별 구성 관리**: dev/staging/prod 환경 분리

#### 모니터링 강화
- [ ] **분산 추적(Distributed Tracing)**: Jaeger, Zipkin
- [ ] **로그 중앙화**: ELK Stack (Elasticsearch + Logstash + Kibana) 또는 Loki
- [ ] **알림 설정**: Slack, PagerDuty 연동
- [ ] **SLO/SLA 대시보드**: 가용성 목표 모니터링
- [ ] **비용 모니터링**: 클라우드 비용 추적

#### 네트워크/보안
- [ ] **WAF(Web Application Firewall)**: SQL Injection, XSS 방어
- [ ] **DDoS 방어**: Cloudflare, AWS Shield
- [ ] **TLS/HTTPS 적용**: SSL 인증서 관리
- [ ] **API Gateway**: Kong, AWS API Gateway (인증, 레이트리밋 중앙화)
- [ ] **VPC 네트워크 분리**: Public/Private 서브넷

#### 파일 스토리지
- [ ] **클라우드 스토리지**: AWS S3, GCS (현재 로컬 저장)
- [ ] **CDN 연동**: CloudFront, CloudFlare (이미지 배포)
- [ ] **이미지 리사이징**: On-the-fly 리사이징 또는 사전 생성

---

### 5. 기타 보완 사항

#### 문서화
- [ ] **API 문서 고도화**: 예제 요청/응답, 에러 케이스 상세 설명
- [ ] **시스템 설계 문서(ADR)**: 아키텍처 결정 기록
- [ ] **온보딩 가이드**: 신규 개발자용 문서
- [ ] **운영 매뉴얼**: 장애 대응 절차, 배포 가이드

#### 성능 최적화
- [ ] **데이터베이스 쿼리 최적화**: 슬로우 쿼리 모니터링 및 개선
- [ ] **커넥션 풀 튜닝**: HikariCP 상세 설정
- [ ] **JVM 튜닝**: GC 설정, 힙 사이즈 최적화
- [ ] **캐시 히트율 모니터링**: 캐시 전략 지속 개선

#### 국제화
- [ ] **다국어 지원(i18n)**: 메시지, 에러 다국어화
- [ ] **다중 통화 지원**: 환율 관리, 통화 변환
- [ ] **타임존 처리**: 글로벌 서비스 시 시간 처리

#### 확장성
- [ ] **마이크로서비스 분리**: 주문/결제/상품/회원 서비스 분리 고려
- [ ] **API 버전 관리**: 하위 호환성 유지
- [ ] **Feature Flag**: 기능별 On/Off 제어

#### 법적/비즈니스
- [ ] **세금 계산**: 부가세, 면세 상품 처리
- [ ] **전자세금계산서 발행**
- [ ] **현금영수증 발급**
- [ ] **에스크로 서비스**: 구매자 보호

---

### 우선순위 권장

실제 운영 전환 시 아래 순서로 진행을 권장합니다:

| 순위 | 항목 | 이유 |
|------|------|------|
| 1 | **결제 시스템** | 수익 창출의 핵심 |
| 2 | **보안 강화** | 개인정보/결제정보 보호 필수 |
| 3 | **테스트 커버리지** | 서비스 안정성 확보 |
| 4 | **모니터링 강화** | 장애 조기 감지 및 대응 |
| 5 | **백업/복구** | 데이터 손실 방지 |
| 6 | **CI/CD 파이프라인** | 안정적인 배포 프로세스 |
| 7 | **클라우드 스토리지** | 확장성 있는 파일 관리 |
| 8 | **배송 연동** | 고객 경험 향상 |
| 9 | **쿠폰/적립금** | 마케팅 도구 |
| 10 | **리뷰 시스템** | 상품 신뢰도 확보 |
