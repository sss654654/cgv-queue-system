# CGV 대기열 시스템 - AWS 인프라 구축 (Terraform)

> 대규모 트래픽을 처리하는 영화 예매 대기열 시스템의 AWS 인프라를 Terraform으로 구현한 IaC(Infrastructure as Code) 프로젝트입니다.

[![Terraform](https://img.shields.io/badge/Terraform-1.x-7B42BC?logo=terraform)](https://www.terraform.io/)
[![AWS](https://img.shields.io/badge/AWS-Cloud-FF9900?logo=amazon-aws)](https://aws.amazon.com/)
[![EKS](https://img.shields.io/badge/EKS-Fargate-FF9900?logo=amazon-eks)](https://aws.amazon.com/eks/)

## 📑 목차
- [프로젝트 개요](#-프로젝트-개요)
- [아키텍처](#-아키텍처)
- [핵심 구현 내용](#-핵심-구현-내용)
- [인프라 구성 요소](#️-인프라-구성-요소)
- [주요 트러블슈팅](#-주요-트러블슈팅)
- [배운 점](#-배운-점)

---

## 🎯 프로젝트 개요

### 담당 역할
**DevOps Engineer & Backend Developer**
- 개발계 AWS 인프라 전체 설계 및 Terraform 코드 작성
- VPC 네트워크 아키텍처 구성 (서브넷 분리, 라우팅, 보안그룹)
- EKS Fargate 클러스터 구축 및 VPC 엔드포인트 설정
- GitLab CI/CD와 연동된 컨테이너 배포 파이프라인 구성

### 기술 스택
```
Infrastructure as Code  │  Terraform
Cloud Platform         │  AWS (VPC, EKS, ECR, ElastiCache, RDS Aurora, NAT Gateway)
Container Orchestration │  Kubernetes (EKS Fargate)
CI/CD                  │  GitLab CI/CD + ArgoCD
Networking             │  VPC Endpoints, Security Groups, Route Tables
```

### 인프라 목표
1. **고가용성**: 다중 가용영역을 활용한 장애 대응
2. **보안**: Private Subnet 기반 격리된 네트워크 환경
3. **비용 효율**: NAT Gateway 최소화 및 VPC Endpoint 활용
4. **확장성**: EKS Fargate를 통한 서버리스 컨테이너 운영

---

## 🏗 아키텍처

### 전체 인프라 다이어그램
```
┌─────────────────────────────────────────────────────────────────────┐
│                          AWS Cloud (VPC)                             │
│                                                                       │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Public Subnet (ap-northeast-2a)                             │   │
│  │  ┌────────────────┐      ┌────────────────┐                 │   │
│  │  │  NAT Gateway   │      │   Client VPN   │                 │   │
│  │  │  (for EKS)     │←─────│   Endpoint     │                 │   │
│  │  └────────────────┘      └────────────────┘                 │   │
│  │         ↓                        ↓                            │   │
│  │    Internet Gateway      (Developers Access)                 │   │
│  └──────────────┬──────────────────────────────────────────────┘   │
│                 │                                                    │
│  ┌──────────────┴──────────────────────────────────────────────┐   │
│  │  Private Subnet - GitLab (ap-northeast-2a)                   │   │
│  │  ┌──────────────────────────────────────────────────────┐   │   │
│  │  │  GitLab EC2                                           │   │   │
│  │  │  - CI/CD Pipeline Runner                             │   │   │
│  │  │  - Docker Build & Push to ECR                        │   │   │
│  │  └──────────────────────────────────────────────────────┘   │   │
│  │         │                                                     │   │
│  │         └──→ VPC Endpoint (ECR API/DKR)                     │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Private Subnet - EKS Fargate (ap-northeast-2a, 2c)         │   │
│  │  ┌──────────────────────────────────────────────────────┐   │   │
│  │  │  EKS Fargate Pods                                     │   │   │
│  │  │  ┌─────────────┐  ┌─────────────┐  ┌─────────────┐  │   │   │
│  │  │  │ Backend API │  │   Redis     │  │   Kinesis   │  │   │   │
│  │  │  │   (Spring)  │  │  Consumer   │  │  Producer   │  │   │   │
│  │  │  └─────────────┘  └─────────────┘  └─────────────┘  │   │   │
│  │  └──────────────────────────────────────────────────────┘   │   │
│  │         │                                                     │   │
│  │         ├──→ VPC Endpoint (ECR API/DKR) ──→ ECR             │   │
│  │         ├──→ VPC Endpoint (S3 Gateway)   ──→ S3             │   │
│  │         └──→ NAT Gateway ──→ Internet (외부 API 호출)       │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  Private Subnet - Database (ap-northeast-2a)                 │   │
│  │  ┌──────────────────────────────────────────────────────┐   │   │
│  │  │  RDS Aurora MySQL                                     │   │   │
│  │  │  - Read/Write Separation                             │   │   │
│  │  └──────────────────────────────────────────────────────┘   │   │
│  │  ┌──────────────────────────────────────────────────────┐   │   │
│  │  │  ElastiCache Redis                                    │   │   │
│  │  │  - Session & Queue Management                        │   │   │
│  │  └──────────────────────────────────────────────────────┘   │   │
│  └─────────────────────────────────────────────────────────────┘   │
│                                                                      │
│  ┌─────────────────────────────────────────────────────────────┐   │
│  │  AWS Services                                                │   │
│  │  - ECR (Container Registry)                                  │   │
│  │  - Kinesis Data Streams (Event Bus)                         │   │
│  │  - Route53 (DNS)                                             │   │
│  └─────────────────────────────────────────────────────────────┘   │
└──────────────────────────────────────────────────────────────────────┘

                    ↓ 
          [Internet Users]
```

### 네트워크 설계 철학

#### 1. 서브넷 분리 전략
```
10.0.0.0/16 (VPC CIDR)
├── 10.0.1.0/24   : Public Subnet (NAT Gateway)
├── 10.0.10.0/24  : Private Subnet - GitLab
├── 10.0.20.0/24  : Private Subnet - Database (RDS, ElastiCache)
└── 10.0.30.0/24  : Private Subnet - EKS Fargate (ap-northeast-2a)
    10.0.31.0/24  : Private Subnet - EKS Fargate (ap-northeast-2c)
```

**설계 근거:**
- GitLab과 EKS를 별도 서브넷으로 분리하여 CI/CD 파이프라인과 운영 환경 격리
- Database 전용 서브넷 구성으로 데이터 계층 보안 강화
- EKS는 AWS 요구사항에 따라 최소 2개 가용영역 구성 (비용 절감을 위해 2a만 실사용)

#### 2. 라우팅 테이블 설계

**Public Subnet 라우팅**
```hcl
Destination: 0.0.0.0/0 → Internet Gateway
```
- NAT Gateway가 배치되어 Private Subnet의 Outbound 트래픽 처리

**EKS Fargate Private Subnet 라우팅**
```hcl
Destination: 0.0.0.0/0 → NAT Gateway
```
- 외부 API 호출, 소프트웨어 업데이트 등을 위한 인터넷 Outbound 경로
- VPC Endpoint로 처리 가능한 트래픽(ECR, S3)은 NAT를 거치지 않음

**GitLab/Database Private Subnet 라우팅**
```hcl
No Internet Route (isolated)
```
- GitLab은 VPC Endpoint로만 ECR 통신
- Database는 완전히 격리된 환경

---

## 🔑 핵심 구현 내용

### 1. VPC 엔드포인트를 통한 비용 최적화

#### 왜 VPC 엔드포인트를 사용했는가?

**문제 상황:**
- EKS Fargate와 GitLab이 ECR에서 컨테이너 이미지를 Pull할 때 NAT Gateway를 거치면 **데이터 전송 비용** 발생
- NAT Gateway는 시간당 요금 + 데이터 처리 요금 부과

**해결 방법:**
```hcl
# GitLab → ECR 통신용 인터페이스 엔드포인트
resource "aws_vpc_endpoint" "gitlab_ecr_dkr" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.dkr"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.gitlab_private_subnet.id]
  security_group_ids = [aws_security_group.gitlab_sg.id]
}

resource "aws_vpc_endpoint" "gitlab_ecr_api" {
  vpc_id            = aws_vpc.dev_vpc.id
  service_name      = "com.amazonaws.ap-northeast-2.ecr.api"
  vpc_endpoint_type = "Interface"
  subnet_ids        = [aws_subnet.gitlab_private_subnet.id]
  security_group_ids = [aws_security_group.gitlab_sg.id]
}

# EKS Fargate → S3 통신용 게이트웨이 엔드포인트
resource "aws_vpc_endpoint" "s3_gateway_endpoint" {
  vpc_id = aws_vpc.dev_vpc.id
  service_name = "com.amazonaws.ap-northeast-2.s3"
  route_table_ids = [aws_route_table.eks_fargate_private_route_table_a.id]
}
```

**ECR 엔드포인트가 2개인 이유:**
1. `ecr.api`: ECR 리포지토리 정보 조회, 인증 토큰 발급 (Control Plane)
2. `ecr.dkr`: Docker 이미지 데이터 실제 전송 (Data Plane)

**효과:**
- NAT Gateway 데이터 전송량 감소 → 비용 절감
- Private 트래픽으로 보안 강화
- 네트워크 지연 시간 단축

### 2. 보안 그룹 설계

#### GitLab Security Group
```hcl
resource "aws_security_group" "gitlab_sg" {
  name = "dev-gitlab-sg"
  vpc_id = aws_vpc.dev_vpc.id

  # Inbound: Client VPN에서만 SSH 접속 허용
  ingress {
    from_port   = 22
    to_port     = 22
    protocol    = "tcp"
    cidr_blocks = ["10.8.0.0/22"]  # Client VPN CIDR
  }

  # Outbound: ECR 엔드포인트, 인터넷 허용
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
```

**설계 근거:**
- GitLab은 프라이빗 서브넷에 위치하여 직접 인터넷 노출 없음
- Client VPN을 통해서만 개발자 접근 가능 (보안 강화)

#### EKS Fargate Security Group
```hcl
resource "aws_security_group" "eks_fargate_sg" {
  name = "dev-eks-fargate-sg"
  vpc_id = aws_vpc.dev_vpc.id

  # Inbound: VPC 내부 통신만 허용
  ingress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["10.0.0.0/16"]
  }

  # Outbound: 모든 외부 통신 허용 (API 호출, ECR Pull 등)
  egress {
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }
}
```

### 3. NAT Gateway 최적화 전략

#### 비용 절감 설계
```hcl
# ap-northeast-2a에만 NAT Gateway 생성
resource "aws_nat_gateway" "dev_nat_gateway_a" {
  allocation_id = aws_eip.nat_gateway_eip_a.id
  subnet_id     = aws_subnet.public_subnet_a.id
}

# ap-northeast-2c의 EKS Fargate도 2a의 NAT 사용
resource "aws_route" "eks_fargate_nat_route_c" {
  route_table_id         = aws_route_table.eks_fargate_private_route_table_c.id
  destination_cidr_block = "0.0.0.0/0"
  nat_gateway_id         = aws_nat_gateway.dev_nat_gateway_a.id
}
```

**트레이드오프 분석:**
- ✅ **장점**: NAT Gateway 비용 50% 절감 (1개만 운영)
- ⚠️ **단점**: ap-northeast-2a 장애 시 2c의 Outbound 트래픽 영향
- 💡 **판단**: 개발 환경 특성상 비용 절감이 우선순위

---

## 🛠️ 인프라 구성 요소

### Terraform 파일 구조
```
terraform/
├── vpc.tf                 # VPC, Subnet, IGW 정의
├── routing.tf             # Route Table, NAT Gateway
├── security_groups.tf     # Security Group 규칙
├── endpoints.tf           # VPC Endpoints (ECR, S3)
├── ec2_instance.tf        # GitLab EC2 인스턴스
├── eks.tf                 # EKS Fargate 클러스터
├── rds.tf                 # Aurora MySQL
├── elasticache.tf         # Redis 클러스터
├── variables.tf           # 변수 정의
└── outputs.tf             # Output 값
```

### 주요 리소스

| 리소스 | 용도 | 위치 |
|--------|------|------|
| EKS Fargate | 컨테이너 오케스트레이션 | Private Subnet (2a, 2c) |
| GitLab EC2 | CI/CD 파이프라인 실행 | Private Subnet (2a) |
| RDS Aurora | MySQL 데이터베이스 (Read/Write 분리) | Private Subnet (2a) |
| ElastiCache | Redis 클러스터 (Session, Queue) | Private Subnet (2a) |
| NAT Gateway | Private → Internet Outbound | Public Subnet (2a) |
| VPC Endpoints | Private → AWS Services | GitLab, EKS Subnet |
| Client VPN | 개발자 VPC 접속 | Public Subnet |

---

## 🚨 주요 트러블슈팅

### 1. EKS API 서버 접속 불가 문제

#### 문제 상황
ArgoCD 배포 후 `api.peacemaker.kr` 도메인 접속 시 `CONNECTION_TIMED_OUT` 발생

#### 원인 분석
1. **AWS Load Balancer Controller Deadlock**
   - 수동으로 ALB를 삭제한 후, Controller가 Security Group 정리 과정에서 멈춤
   - 새로운 ALB 생성 불가 상태

2. **진단 과정**
```bash
# 1단계: Kubernetes 리소스 확인
kubectl get pods,service,ingress -n cgv-api
# → Pod, Service는 정상

# 2단계: Ingress 상세 정보 확인
kubectl describe ingress cgv-api-platform-ingress -n cgv-api
# → ADDRESS 필드가 비어있음 (ALB가 없음)

# 3단계: Load Balancer Controller 로그 확인
kubectl logs -n kube-system deployment/aws-load-balancer-controller
# → "failed to delete securityGroup: timed out" 반복 발생
```

#### 해결 방법
```bash
# Ingress의 Finalizer 강제 제거 (Controller의 Deadlock 해소)
kubectl patch ingress cgv-api-platform-ingress -n cgv-api \
  -p '{"metadata":{"finalizers":[]}}' --type=merge

# Ingress 재생성 (ArgoCD Sync)
# Controller가 새로운 ALB를 정상적으로 프로비저닝
```

#### 교훈
- Kubernetes Controller가 관리하는 AWS 리소스는 **절대 수동 삭제 금지**
- Infrastructure as Code 원칙 준수의 중요성

### 2. EKS Subnet 태그 필수 설정

#### 문제 상황
AWS Load Balancer Controller가 ALB를 생성할 서브넷을 찾지 못함

#### 원인
EKS 클러스터와 서브넷을 연결하는 **태그 누락**

#### 해결 방법
```hcl
# Public Subnet (Internet-Facing ALB용)
resource "aws_subnet" "public_subnet_a" {
  # ... 기본 설정 ...
  
  tags = {
    Name = "dev-public-subnet-a"
    "kubernetes.io/cluster/devops-dev-eks-cluster" = "shared"  # 클러스터 공유 태그
    "kubernetes.io/role/elb" = "1"                             # Public ALB용 태그
  }
}

# Private Subnet (Internal ALB용)
resource "aws_subnet" "eks_fargate_private_subnet_a" {
  # ... 기본 설정 ...
  
  tags = {
    Name = "dev-eks-fargate-private-subnet-a"
    "kubernetes.io/cluster/devops-dev-eks-cluster" = "shared"
    "kubernetes.io/role/internal-elb" = "1"                    # Private ALB용 태그
  }
}
```

#### 동작 원리
1. Ingress에 `alb.ingress.kubernetes.io/scheme: internet-facing` 설정
2. Controller가 `kubernetes.io/role/elb` 태그를 가진 서브넷 탐색
3. 해당 서브넷에 ALB 생성 및 배포

### 3. GitLab과 EKS의 ECR 엔드포인트 분리

#### 설계 이슈
GitLab과 EKS가 동일한 ECR에 접근하지만, **별도의 VPC 엔드포인트** 필요

#### 이유
1. **서브넷 격리**: GitLab과 EKS는 다른 Private Subnet에 위치
2. **보안 그룹 분리**: 각 워크로드의 트래픽 제어 규칙이 다름
3. **네트워크 효율**: 각 서브넷에서 최단 경로로 ECR 접근

```hcl
# GitLab용 ECR 엔드포인트
resource "aws_vpc_endpoint" "gitlab_ecr_dkr" {
  subnet_ids         = [aws_subnet.gitlab_private_subnet.id]
  security_group_ids = [aws_security_group.gitlab_sg.id]
}

# EKS Fargate용 ECR 엔드포인트 (별도)
resource "aws_vpc_endpoint" "eks_fargate_ecr_dkr" {
  subnet_ids         = [aws_subnet.eks_fargate_private_subnet_a.id]
  security_group_ids = [aws_security_group.eks_fargate_sg.id]
}
```

---

## 💡 배운 점

### 1. Infrastructure as Code의 가치
- 수동 변경으로 인한 장애 경험을 통해 IaC의 중요성 체감
- Terraform State 관리와 변경 이력 추적의 필요성

### 2. 클라우드 네트워킹 설계 역량
- VPC, Subnet, Route Table의 유기적 관계 이해
- Public/Private 분리를 통한 보안 강화 전략
- VPC Endpoint를 활용한 비용 최적화 기법

### 3. AWS 서비스 간 통합 이해
- EKS와 AWS Load Balancer Controller의 동작 원리
- Kubernetes Ingress → ALB → Target Group → Pod 전체 흐름
- IRSA(IAM Roles for Service Accounts)를 통한 권한 관리

### 4. 트러블슈팅 방법론
- 레이어별 진단 (Kubernetes → AWS 네트워크 → 애플리케이션)
- 로그 분석과 이벤트 추적을 통한 근본 원인 파악
- 임시 조치와 재발 방지책의 병행

---

## 📊 개선 방향

### 단기
- [ ] Terraform Backend를 S3 + DynamoDB로 구성하여 State 잠금 처리
- [ ] Terraform Module화로 재사용성 향상
- [ ] AWS WAF 연동을 통한 DDoS 방어

### 중장기
- [ ] Multi-Region 배포를 위한 재해 복구(DR) 환경 구축
- [ ] Terraform Cloud를 활용한 협업 워크플로우 구성
- [ ] Infrastructure 비용 최적화 자동화 (Spot Instances, Reserved Instances)

---

## 🔗 관련 레포지토리
- **Backend**: [cgv-backend](링크) - Spring Boot 대기열 시스템
- **Frontend**: [cgv-frontend](링크) - React 실시간 대기열 UI

---

## 📝 라이선스
이 프로젝트는 학습 목적으로 제작되었습니다.

---

## ✉️ Contact
- **Email**: your-email@example.com
- **LinkedIn**: [프로필 링크]
- **Blog**: [기술 블로그 링크]
