# 온라인 마켓플레이스 백엔드 API

온라인 마켓플레이스의 백엔드 API입니다. 판매자는 상품을 등록하고, 구매자는 상품을 검색하여 주문할 수 있습니다.

## 기술 스택

- **Language**: Kotlin 1.9
- **Framework**: Spring Boot 3.2
- **Database**: H2 (로컬), MySQL 8.0 (Docker)
- **ORM**: Spring Data JPA
- **Security**: Spring Security + JWT
- **Documentation**: SpringDoc OpenAPI (Swagger)
- **Build**: Gradle 8.5
- **Container**: Docker & Docker Compose

### 기술 선택 이유

- **Kotlin**: Java보다 간결한 문법, null-safety, data class 등 생산성 향상
- **Spring Boot 3.2**: 최신 LTS 버전으로 안정성과 성능 보장
- **멀티 모듈 구조**: 의존성 역전 원칙(DIP) 적용, 모듈 간 명확한 책임 분리
- **H2/MySQL**: 로컬 개발의 편의성과 프로덕션 환경의 안정성 모두 지원

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

### 로컬 실행 (H2)

```bash
# 빌드
./gradlew build

# 실행
./gradlew :marketplace-api:bootRun --args='--spring.profiles.active=local'
```

### Docker Compose 실행

```bash
# 빌드 및 실행
docker-compose up -d

# 로그 확인
docker-compose logs -f app

# 종료
docker-compose down
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

### 구현된 기능
- [x] JWT 기반 인증 (Access Token 1시간, Refresh Token 7일)
- [x] 역할 기반 접근 제어 (BUYER, SELLER, ADMIN)
- [x] 상품 CRUD 및 이미지 업로드
- [x] 상품 검색/필터링 (키워드, 카테고리, 가격대, 상태)
- [x] 주문 생성 및 취소 (동시성 처리: 비관적 락)
- [x] 주문 상태 관리 (PENDING → CONFIRMED → SHIPPED → DELIVERED)
- [x] 비동기 알림 이벤트 (로그로 대체)
- [x] 인기 상품 캐싱 (로컬 캐시)
- [x] GlobalExceptionHandler
- [x] 요청 로깅 (Request ID)
- [x] Swagger API 문서화
- [x] Docker Compose 지원
- [x] 테스트 코드 (Controller, Service)

### 선택 구현 사항
- [x] **멀티 모듈 구조**: api/domain/infra/common 분리, 의존성 역전 적용
- [x] **Kotlin**: 전체 코드를 Kotlin으로 구현
- [x] **이벤트 기반 알림**: Spring Event + @TransactionalEventListener

## 테스트

```bash
# 전체 테스트 실행
./gradlew test

# 특정 모듈 테스트
./gradlew :marketplace-api:test
```

## 환경 변수 (Docker)

| 변수명          | 기본값                | 설명              |
|-----------------|----------------------|-------------------|
| MYSQL_HOST      | mysql                | MySQL 호스트       |
| MYSQL_PORT      | 3306                 | MySQL 포트         |
| MYSQL_DATABASE  | marketplace          | 데이터베이스 이름   |
| MYSQL_USER      | marketplace          | 데이터베이스 사용자 |
| MYSQL_PASSWORD  | marketplace123       | 데이터베이스 비밀번호|
| JWT_SECRET      | (기본값 있음)         | JWT 서명 키        |

## 추가 구현 사항

### 동시성 처리
주문 생성 시 재고 차감에 비관적 락(Pessimistic Lock)을 적용하여 동시성 문제를 해결했습니다.

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("SELECT p FROM Product p WHERE p.id = :id")
fun findByIdWithLock(@Param("id") id: Long): Optional<Product>
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
