# CGV Queue System

10만 동시 접속 트래픽을 처리하는 영화 예매 대기열 시스템

## 프로젝트 개요

영화 티켓팅 오픈 시 순간적으로 폭증하는 트래픽을 안정적으로 처리하기 위한 대기열 시스템입니다.
Redis Sorted Set으로 실시간 순위를 관리하고, Kinesis로 비동기 이벤트를 처리하며, WebSocket으로 사용자에게 실시간 알림을 제공합니다.

## 아키텍처

```
사용자 → ALB → EKS Pod (Spring Boot)
                    ↓
               Redis (대기열 순위)
               Kinesis (이벤트 스트림)
                    ↓
               WebSocket → "입장하세요" 실시간 알림
```

## 기술 스택

| 영역 | 기술 |
|------|------|
| Backend | Java 17, Spring Boot 3.3, WebSocket (STOMP) |
| Frontend | React, Vite |
| Infrastructure | Terraform, AWS (VPC, EKS, RDS Aurora, ElastiCache, Kinesis) |
| CI/CD | GitLab CI, ArgoCD, Helm |
| Database | MySQL (Aurora), Redis |

## 프로젝트 구조

```
cgv-queue-system/
├── backend/      # Spring Boot 백엔드 (대기열 핵심 로직)
├── frontend/     # React 프론트엔드
├── infra/        # Terraform 인프라 코드
└── platform/     # Kubernetes Helm Chart
```

## 핵심 기능

### 대기열 시스템
- **실시간 순위 관리**: Redis Sorted Set으로 O(log N) 순위 조회
- **비동기 이벤트 처리**: Kinesis Data Streams로 입장 이벤트 발행/소비
- **실시간 알림**: WebSocket(STOMP)으로 입장 알림 전송
- **동적 세션 관리**: Pod 수에 따라 최대 동시 세션 자동 조절

### 인프라
- **VPC 네트워크**: Public/Private 서브넷 분리, 보안 그룹 설계
- **VPC Endpoint**: ECR, S3 접근 시 NAT Gateway 비용 절감
- **Client VPN**: 개발자의 Private 리소스 접근

### CI/CD
- **GitLab CI**: 코드 품질 검사 → Maven 빌드 → Docker 이미지 → ECR 푸시
- **ArgoCD**: GitOps 방식 자동 배포, Drift Detection

## 로컬 개발 환경

```bash
# Backend
cd backend
docker-compose up -d  # LocalStack, Redis, MySQL
./mvnw spring-boot:run -Dspring.profiles.active=local

# Frontend
cd frontend
npm install
npm run dev
```

## 문서

상세 기술 문서는 아래를 참고하세요:
- [Backend README](./backend/README.md)
- [Infrastructure README](./infra/README.md)
- [Platform README](./platform/README.md)
