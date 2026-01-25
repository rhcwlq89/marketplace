# Docker & Kubernetes 스터디 가이드

이 문서는 마켓플레이스 프로젝트의 Docker와 Kubernetes 구성을 통해 컨테이너 기술을 학습합니다.

---

## 1. 핵심 개념

### 1.1 왜 컨테이너를 사용하는가?

**문제**: "내 컴퓨터에서는 되는데..."
- 개발 환경과 운영 환경의 차이 (OS, 라이브러리 버전 등)
- 팀원마다 다른 환경 설정
- 서버 세팅에 많은 시간 소요

**해결**: 컨테이너로 환경을 패키징
```
[애플리케이션] + [필요한 모든 것] = [컨테이너 이미지]
     ↓
어디서든 동일하게 실행
```

### 1.2 Docker vs Kubernetes

| 구분 | Docker | Kubernetes (K8s) |
|------|--------|------------------|
| 역할 | 컨테이너를 만들고 실행 | 여러 컨테이너를 관리/조율 |
| 비유 | 택배 상자를 만드는 공장 | 택배 물류센터 |
| 규모 | 단일 서버 | 여러 서버 클러스터 |
| 용도 | 개발/테스트 | 프로덕션 배포 |

---

## 2. Docker 이해하기

### 2.1 Dockerfile 분석

우리 프로젝트의 `Dockerfile`:

```dockerfile
# ===== 1단계: 빌드 스테이지 =====
FROM gradle:8.5-jdk17 AS builder    # Gradle + JDK 17 이미지를 기반으로
WORKDIR /app                         # 작업 디렉토리 설정

# Gradle 파일 먼저 복사 (의존성 캐싱용)
COPY build.gradle.kts settings.gradle.kts ./
COPY gradle ./gradle
COPY marketplace-common/build.gradle.kts ./marketplace-common/
# ... 나머지 build.gradle.kts 파일들

RUN gradle dependencies --no-daemon || true    # 의존성 다운로드 (캐시됨)

COPY . .                                        # 소스코드 복사
RUN gradle :marketplace-api:bootJar --no-daemon -x test  # JAR 빌드

# ===== 2단계: 실행 스테이지 =====
FROM eclipse-temurin:17-jre          # 가벼운 JRE 이미지 (빌드 도구 없음)
WORKDIR /app

# 보안: 루트가 아닌 일반 사용자로 실행
RUN groupadd -g 1001 appgroup && useradd -u 1001 -g appgroup appuser
USER appuser

# 빌드 스테이지에서 JAR 파일만 복사
COPY --from=builder /app/marketplace-api/build/libs/*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

#### 멀티 스테이지 빌드의 장점

```
빌드 스테이지 (gradle:8.5-jdk17)     실행 스테이지 (eclipse-temurin:17-jre)
┌─────────────────────────────┐     ┌─────────────────────────────┐
│ Gradle (200MB+)             │     │ JRE만 (가벼움)               │
│ JDK (빌드 도구 포함)          │ ──▶ │ app.jar (우리 앱)           │
│ 소스코드 전체                 │     │                             │
│ 의존성 캐시                   │     │ 최종 이미지 크기: ~300MB     │
└─────────────────────────────┘     └─────────────────────────────┘
        버려짐                              실제 배포됨
```

### 2.2 docker-compose.yml 분석

Docker Compose는 여러 컨테이너를 한 번에 정의하고 실행합니다.

```yaml
services:
  # ===== MySQL 데이터베이스 =====
  mysql:
    image: mysql:8.0              # Docker Hub에서 공식 이미지 사용
    container_name: marketplace-mysql
    environment:                   # 환경 변수 설정
      MYSQL_ROOT_PASSWORD: root123
      MYSQL_DATABASE: marketplace
      MYSQL_USER: marketplace
      MYSQL_PASSWORD: marketplace123
    ports:
      - "3306:3306"               # 호스트:컨테이너 포트 매핑
    volumes:
      - mysql-data:/var/lib/mysql  # 데이터 영속화
    healthcheck:                   # 컨테이너 상태 확인
      test: ["CMD", "mysqladmin", "ping", "-h", "localhost"]
      interval: 10s
    networks:
      - marketplace-network        # 같은 네트워크의 컨테이너끼리 통신

  # ===== 우리 애플리케이션 =====
  app:
    build:
      context: .
      dockerfile: Dockerfile       # 위의 Dockerfile로 이미지 빌드
    environment:
      SPRING_PROFILES_ACTIVE: docker
      MYSQL_HOST: mysql            # 컨테이너 이름으로 접근 (DNS 역할)
      REDIS_HOST: redis
      KAFKA_BOOTSTRAP_SERVERS: kafka:29092
    depends_on:
      mysql:
        condition: service_healthy  # MySQL이 준비된 후 시작
    networks:
      - marketplace-network

volumes:                           # 명명된 볼륨 정의
  mysql-data:                      # 컨테이너 삭제해도 데이터 유지

networks:
  marketplace-network:             # 가상 네트워크 생성
    driver: bridge
```

#### 네트워크 구조

```
┌─────────────────── marketplace-network ───────────────────┐
│                                                           │
│  ┌─────────┐   ┌─────────┐   ┌─────────┐   ┌─────────┐  │
│  │  mysql  │   │  redis  │   │  kafka  │   │   app   │  │
│  │  :3306  │   │  :6379  │   │  :9092  │   │  :8080  │  │
│  └─────────┘   └─────────┘   └─────────┘   └─────────┘  │
│       ▲             ▲             ▲             │        │
│       └─────────────┴─────────────┴─────────────┘        │
│                컨테이너 이름으로 서로 접근                  │
└───────────────────────────────────────────────────────────┘
                          │
                    ports: "8080:8080"
                          ▼
                   [호스트 머신]
                   localhost:8080
```

### 2.3 자주 사용하는 Docker 명령어

```bash
# 이미지 빌드
docker build -t marketplace-app:latest .

# 컨테이너 실행
docker run -d -p 8080:8080 marketplace-app:latest

# Docker Compose로 전체 스택 실행
docker-compose up -d

# 로그 확인
docker logs marketplace-app
docker-compose logs -f app

# 컨테이너 내부 접속
docker exec -it marketplace-app /bin/bash

# 정리
docker-compose down           # 컨테이너 중지/삭제
docker-compose down -v        # 볼륨까지 삭제
```

---

## 3. Kubernetes 이해하기

### 3.1 왜 Kubernetes인가?

Docker Compose의 한계:
- 단일 서버에서만 동작
- 컨테이너가 죽으면 수동으로 재시작
- 부하 증가 시 수동으로 스케일 아웃

Kubernetes가 해결:
- 여러 서버에 걸쳐 컨테이너 배포
- 자동 복구 (Self-healing)
- 자동 스케일링
- 롤링 업데이트 / 롤백

### 3.2 Kubernetes 핵심 개념

```
┌─────────────────────────────────────────────────────────────────┐
│                        Kubernetes Cluster                        │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    Namespace: marketplace                  │  │
│  │                                                            │  │
│  │   ┌─────────────┐     ┌─────────────┐     ┌───────────┐  │  │
│  │   │ Deployment  │     │   Service   │     │  Ingress  │  │  │
│  │   │ (mysql)     │────▶│  (mysql)    │     │           │  │  │
│  │   │  replica:1  │     │ ClusterIP   │     │  ┌─────┐  │  │  │
│  │   └─────────────┘     └─────────────┘     │  │ /   │  │  │  │
│  │          │                                 │  │     │──┼──┼──▶ 외부
│  │          ▼                                 │  │     │  │  │
│  │   ┌─────────────┐     ┌─────────────┐     │  └─────┘  │  │  │
│  │   │    Pod      │     │   Service   │     │           │  │  │
│  │   │ ┌─────────┐ │     │ (app)       │─────┤           │  │  │
│  │   │ │Container│ │────▶│ ClusterIP   │     └───────────┘  │  │
│  │   │ │ (mysql) │ │     └─────────────┘                    │  │
│  │   │ └─────────┘ │                                        │  │
│  │   └─────────────┘                                        │  │
│  │          │                                                │  │
│  │          ▼                                                │  │
│  │   ┌─────────────┐                                        │  │
│  │   │     PVC     │  ◀── 데이터 영속화                      │  │
│  │   │ (mysql-pvc) │                                        │  │
│  │   └─────────────┘                                        │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

#### 주요 리소스 설명

| 리소스 | 역할 | Docker Compose 대응 |
|--------|------|---------------------|
| **Namespace** | 리소스를 논리적으로 분리 | - |
| **Pod** | 컨테이너를 감싸는 최소 단위 | container |
| **Deployment** | Pod의 개수와 상태를 관리 | service (일부) |
| **Service** | Pod에 접근하는 고정 주소 제공 | networks + ports |
| **ConfigMap** | 설정 정보 저장 | environment |
| **Secret** | 민감한 정보 저장 | environment (암호화) |
| **PVC** | 영구 저장소 요청 | volumes |
| **Ingress** | 외부에서 클러스터로 진입점 | ports |

### 3.3 K8s 매니페스트 분석

#### 3.3.1 Namespace (k8s/namespace.yaml)

```yaml
apiVersion: v1
kind: Namespace
metadata:
  name: marketplace   # 모든 리소스를 이 네임스페이스에 배포
```

#### 3.3.2 MySQL Deployment 분석 (k8s/mysql/deployment.yaml)

```yaml
# ===== 1. 영구 저장소 요청 =====
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: mysql-pvc
  namespace: marketplace
spec:
  accessModes:
    - ReadWriteOnce          # 하나의 Pod만 쓰기 가능
  resources:
    requests:
      storage: 1Gi           # 1GB 요청

---
# ===== 2. 비밀 정보 =====
apiVersion: v1
kind: Secret
metadata:
  name: mysql-secret
  namespace: marketplace
type: Opaque
stringData:                  # Base64 인코딩됨
  MYSQL_ROOT_PASSWORD: root123
  MYSQL_DATABASE: marketplace
  MYSQL_USER: marketplace
  MYSQL_PASSWORD: marketplace123

---
# ===== 3. 배포 설정 =====
apiVersion: apps/v1
kind: Deployment
metadata:
  name: mysql
  namespace: marketplace
spec:
  replicas: 1                # Pod 1개 유지
  selector:
    matchLabels:
      app: mysql             # 이 레이블을 가진 Pod를 관리
  template:                  # Pod 템플릿
    metadata:
      labels:
        app: mysql           # Service가 이 레이블로 찾음
    spec:
      containers:
        - name: mysql
          image: mysql:8.0
          ports:
            - containerPort: 3306
          envFrom:
            - secretRef:
                name: mysql-secret   # Secret에서 환경변수 주입
          volumeMounts:
            - name: mysql-storage
              mountPath: /var/lib/mysql
          # 준비 상태 확인 (트래픽 받을 준비)
          readinessProbe:
            exec:
              command: ["mysqladmin", "ping", "-h", "localhost"]
            initialDelaySeconds: 30
            periodSeconds: 10
          # 생존 상태 확인 (죽으면 재시작)
          livenessProbe:
            exec:
              command: ["mysqladmin", "ping", "-h", "localhost"]
            initialDelaySeconds: 60
            periodSeconds: 20
      volumes:
        - name: mysql-storage
          persistentVolumeClaim:
            claimName: mysql-pvc     # PVC와 연결

---
# ===== 4. 서비스 (내부 DNS) =====
apiVersion: v1
kind: Service
metadata:
  name: mysql                # 다른 Pod에서 "mysql:3306"으로 접근
  namespace: marketplace
spec:
  selector:
    app: mysql               # 이 레이블의 Pod로 트래픽 전달
  ports:
    - port: 3306
      targetPort: 3306
  type: ClusterIP            # 클러스터 내부에서만 접근 가능
```

#### 3.3.3 App Deployment 분석 (k8s/app/deployment.yaml)

```yaml
# ===== ConfigMap: 일반 설정 =====
apiVersion: v1
kind: ConfigMap
metadata:
  name: app-config
data:
  SPRING_PROFILES_ACTIVE: docker
  MYSQL_HOST: mysql           # K8s Service 이름으로 접근
  MYSQL_PORT: "3306"
  REDIS_HOST: redis
  KAFKA_BOOTSTRAP_SERVERS: kafka:9092

---
# ===== Secret: 민감한 설정 =====
apiVersion: v1
kind: Secret
metadata:
  name: app-secret
stringData:
  MYSQL_USER: marketplace
  MYSQL_PASSWORD: marketplace123
  JWT_SECRET: marketplace-jwt-secret-key-for-docker-minimum-32-characters

---
# ===== Deployment =====
apiVersion: apps/v1
kind: Deployment
metadata:
  name: marketplace-app
spec:
  replicas: 1                 # 프로덕션에서는 2~3개로 증가
  template:
    spec:
      containers:
        - name: app
          image: marketplace-registry:5000/marketplace-app:latest
          ports:
            - containerPort: 8080
          envFrom:
            - configMapRef:
                name: app-config    # ConfigMap의 모든 값을 환경변수로
            - secretRef:
                name: app-secret    # Secret의 모든 값을 환경변수로
          # 리소스 제한 (중요!)
          resources:
            requests:              # 최소 보장
              memory: "512Mi"
              cpu: "250m"          # 0.25 CPU
            limits:                # 최대 사용
              memory: "1Gi"
              cpu: "1000m"         # 1 CPU

---
# ===== Ingress: 외부 노출 =====
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: marketplace-ingress
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: marketplace-app
                port:
                  number: 80
```

### 3.4 Docker Compose vs Kubernetes 비교

| 개념 | Docker Compose | Kubernetes |
|------|----------------|------------|
| 컨테이너 정의 | `services.app` | `Deployment` + `Pod` |
| 환경 변수 | `environment` | `ConfigMap` + `Secret` |
| 포트 노출 | `ports: "8080:8080"` | `Service` + `Ingress` |
| 데이터 저장 | `volumes: mysql-data:` | `PersistentVolumeClaim` |
| 네트워크 | `networks` | `Service` (DNS 제공) |
| 의존성 | `depends_on` | `initContainers` 또는 Probe |
| 헬스체크 | `healthcheck` | `readinessProbe` + `livenessProbe` |

---

## 4. 프로젝트 실행 가이드

### 4.1 Docker Compose로 실행 (개발용)

```bash
# 전체 스택 시작
docker-compose up -d

# 상태 확인
docker-compose ps

# 로그 확인
docker-compose logs -f app

# 종료
docker-compose down
```

### 4.2 Kubernetes로 실행 (k3d)

```bash
# k3d 클러스터 생성 및 배포
./k8s/deploy.sh

# 상태 확인
kubectl get pods -n marketplace
kubectl get services -n marketplace

# 로그 확인
kubectl logs -l app=marketplace-app -n marketplace

# Pod 내부 접속
kubectl exec -it deployment/marketplace-app -n marketplace -- /bin/sh

# 클러스터 삭제
./k8s/destroy.sh
```

### 4.3 자주 사용하는 kubectl 명령어

```bash
# 리소스 조회
kubectl get pods -n marketplace              # Pod 목록
kubectl get all -n marketplace               # 모든 리소스
kubectl describe pod <pod-name> -n marketplace  # 상세 정보

# 로그
kubectl logs <pod-name> -n marketplace       # 로그 확인
kubectl logs -f <pod-name> -n marketplace    # 실시간 로그

# 디버깅
kubectl exec -it <pod-name> -n marketplace -- /bin/sh  # 컨테이너 접속
kubectl port-forward svc/mysql 3306:3306 -n marketplace  # 포트 포워딩

# 배포
kubectl apply -f k8s/app/deployment.yaml     # 매니페스트 적용
kubectl rollout restart deployment/marketplace-app -n marketplace  # 재시작
kubectl rollout status deployment/marketplace-app -n marketplace   # 상태 확인

# 스케일링
kubectl scale deployment/marketplace-app --replicas=3 -n marketplace
```

---

## 5. 아키텍처 다이어그램

### 5.1 전체 시스템 구조

```
                            [인터넷]
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────┐
│                     Kubernetes Cluster (k3d)                     │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │                  Namespace: marketplace                   │   │
│  │                                                           │   │
│  │   ┌─────────────────────────────────────────────────┐    │   │
│  │   │              Ingress (포트 8080)                 │    │   │
│  │   └─────────────────────────────────────────────────┘    │   │
│  │                           │                               │   │
│  │                           ▼                               │   │
│  │   ┌─────────────────────────────────────────────────┐    │   │
│  │   │           marketplace-app (Spring Boot)         │    │   │
│  │   │                                                  │    │   │
│  │   │  ┌──────────┐ ┌──────────┐ ┌──────────────────┐│    │   │
│  │   │  │ REST API │ │ 캐싱     │ │ 이벤트 발행       ││    │   │
│  │   │  └────┬─────┘ └────┬─────┘ └────────┬─────────┘│    │   │
│  │   └───────│────────────│────────────────│──────────┘    │   │
│  │           │            │                │                │   │
│  │           ▼            ▼                ▼                │   │
│  │   ┌────────────┐ ┌──────────┐ ┌──────────────────┐      │   │
│  │   │   MySQL    │ │  Redis   │ │      Kafka       │      │   │
│  │   │  (DB)      │ │ (캐시)   │ │ (메시지 큐)       │      │   │
│  │   │            │ │          │ │                  │      │   │
│  │   │  ┌──────┐  │ │          │ │                  │      │   │
│  │   │  │ PVC  │  │ │          │ │                  │      │   │
│  │   │  │(1GB) │  │ │          │ │                  │      │   │
│  │   │  └──────┘  │ │          │ │                  │      │   │
│  │   └────────────┘ └──────────┘ └──────────────────┘      │   │
│  │                                                           │   │
│  │   ┌──────────────────────────────────────────────────┐   │   │
│  │   │                  Monitoring                       │   │   │
│  │   │  ┌────────────┐        ┌────────────┐            │   │   │
│  │   │  │ Prometheus │───────▶│  Grafana   │            │   │   │
│  │   │  │  (수집)    │        │  (시각화)  │            │   │   │
│  │   │  │  :9090     │        │  :3000     │            │   │   │
│  │   │  └────────────┘        └────────────┘            │   │   │
│  │   └──────────────────────────────────────────────────┘   │   │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘
```

### 5.2 요청 흐름

```
[사용자 요청]
     │
     ▼
[localhost:8080]
     │
     ▼ k3d 포트 매핑
[k3d Load Balancer]
     │
     ▼
[Ingress Controller]
     │
     ▼ path: /
[Service: marketplace-app]
     │
     ▼ selector: app=marketplace-app
[Pod: marketplace-app-xxx]
     │
     ├──▶ [Service: mysql] ──▶ [Pod: mysql]
     │
     ├──▶ [Service: redis] ──▶ [Pod: redis]
     │
     └──▶ [Service: kafka] ──▶ [Pod: kafka]
```

---

## 6. 면접 대비 Q&A

### Q1. Docker와 VM의 차이점은?

```
Virtual Machine                    Docker Container
┌─────────────────┐               ┌─────────────────┐
│     App A       │               │     App A       │
├─────────────────┤               ├─────────────────┤
│   Guest OS      │               │   (OS 공유)     │
├─────────────────┤               ├─────────────────┤
│   Hypervisor    │               │  Docker Engine  │
├─────────────────┤               ├─────────────────┤
│    Host OS      │               │    Host OS      │
└─────────────────┘               └─────────────────┘

- VM: OS 전체를 가상화 → 무겁고 느림 (GB 단위)
- Container: 프로세스 격리만 → 가볍고 빠름 (MB 단위)
```

### Q2. Kubernetes에서 Pod가 죽으면 어떻게 되나요?

1. **Deployment**가 Pod 상태를 모니터링
2. Pod가 죽으면 자동으로 새 Pod 생성
3. **livenessProbe** 실패 시 컨테이너 재시작
4. **readinessProbe** 실패 시 트래픽에서 제외

```yaml
livenessProbe:        # 살아있는지 확인 → 실패시 재시작
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 90
  periodSeconds: 20

readinessProbe:       # 준비됐는지 확인 → 실패시 트래픽 차단
  httpGet:
    path: /actuator/health
    port: 8080
  initialDelaySeconds: 60
  periodSeconds: 10
```

### Q3. ConfigMap과 Secret의 차이는?

| 구분 | ConfigMap | Secret |
|------|-----------|--------|
| 용도 | 일반 설정 | 민감한 정보 |
| 저장 | 평문 | Base64 인코딩 |
| 예시 | DB 호스트, 포트 | 비밀번호, API 키 |
| 암호화 | X | etcd 암호화 가능 |

### Q4. Service의 종류와 차이점은?

```
ClusterIP (기본값)           NodePort                    LoadBalancer
┌─────────────────┐         ┌─────────────────┐         ┌─────────────────┐
│   클러스터 내부  │         │  노드의 특정 포트 │         │   클라우드 LB   │
│   에서만 접근    │         │  로 외부 노출    │         │   자동 프로비저닝│
│                 │         │                 │         │                 │
│ mysql:3306      │         │ :30080 → :8080  │         │ AWS ELB 등     │
└─────────────────┘         └─────────────────┘         └─────────────────┘
   내부 DB용                   개발/테스트용                 프로덕션용
```

### Q5. 무중단 배포는 어떻게 하나요?

```yaml
spec:
  replicas: 3
  strategy:
    type: RollingUpdate      # 순차적 교체
    rollingUpdate:
      maxSurge: 1            # 추가로 생성할 수 있는 Pod
      maxUnavailable: 0      # 동시에 죽을 수 있는 Pod (0 = 무중단)
```

배포 과정:
```
기존:  [v1] [v1] [v1]
       ↓
추가:  [v1] [v1] [v1] [v2]   # maxSurge: 1
       ↓
교체:  [v1] [v1] [v2] [v2]   # v1 하나 제거
       ↓
완료:  [v2] [v2] [v2]
```

### Q6. Dockerfile에서 레이어 캐싱을 어떻게 활용하나요?

```dockerfile
# 나쁜 예: 소스 변경마다 의존성 다시 다운로드
COPY . .
RUN gradle build

# 좋은 예: 의존성 파일만 먼저 복사
COPY build.gradle.kts settings.gradle.kts ./    # 변경 적음
RUN gradle dependencies                          # 캐시됨

COPY . .                                         # 소스는 자주 변경
RUN gradle build                                 # 의존성은 캐시 활용
```

---

## 7. 트러블슈팅 가이드

### 7.1 Pod가 시작되지 않을 때

```bash
# 1. Pod 상태 확인
kubectl get pods -n marketplace
# STATUS: Pending, CrashLoopBackOff, ImagePullBackOff 등

# 2. 상세 이벤트 확인
kubectl describe pod <pod-name> -n marketplace

# 3. 로그 확인
kubectl logs <pod-name> -n marketplace
kubectl logs <pod-name> -n marketplace --previous  # 이전 컨테이너 로그
```

### 7.2 일반적인 에러와 해결

| 상태 | 원인 | 해결 |
|------|------|------|
| `ImagePullBackOff` | 이미지를 가져올 수 없음 | 이미지 이름/태그 확인, 레지스트리 접근 권한 |
| `CrashLoopBackOff` | 컨테이너가 반복적으로 죽음 | 로그 확인, 리소스 부족, 설정 오류 |
| `Pending` | Pod를 스케줄할 수 없음 | 리소스 부족, PVC 없음 |
| `OOMKilled` | 메모리 부족 | `resources.limits.memory` 증가 |

### 7.3 네트워크 연결 확인

```bash
# Pod 내부에서 다른 서비스 접근 테스트
kubectl exec -it <pod-name> -n marketplace -- /bin/sh

# DNS 확인
nslookup mysql
nslookup redis

# 연결 테스트
nc -zv mysql 3306
nc -zv redis 6379
```

---

## 8. 다음 학습 단계

1. **Helm**: K8s 패키지 매니저 (복잡한 앱 배포 간소화)
2. **Kustomize**: 환경별 설정 관리
3. **Service Mesh (Istio)**: 마이크로서비스 간 통신 관리
4. **GitOps (ArgoCD)**: Git으로 인프라 관리
5. **Prometheus/Grafana**: 모니터링 심화
