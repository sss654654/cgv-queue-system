# =============================================================================
# VPC Endpoints
# S3 Gateway (무료) + ECR API (Interface) + ECR DKR (Interface)
# ECR Pull/Push 트래픽이 NAT Gateway를 거치지 않도록 비용 절감
# =============================================================================

# =============================================================================
# VPC Endpoint Security Group
# cgv-vpc 스택에 정의하는 이유: Endpoint와 SG의 배포 순서 의존성 해소
# cgv-security(Stack 3)보다 cgv-vpc(Stack 2)가 먼저 배포되므로, Endpoint 생성 시
# SG가 이미 존재해야 한다. Node SG 참조는 cgv-security 배포 후 업데이트.
# =============================================================================
resource "aws_security_group" "vpc_endpoint" {
  name        = "cgv-vpc-endpoint-sg"
  description = "VPC Endpoints (ECR, S3) - HTTPS from App Private Subnet"
  vpc_id      = aws_vpc.main.id

  # App Private Subnet CIDR에서 HTTPS 허용
  # Node SG 참조 대신 CIDR 사용: cgv-security보다 먼저 배포되므로
  ingress {
    description = "HTTPS from App Private Subnet A"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["10.0.10.0/24"]
  }

  ingress {
    description = "HTTPS from App Private Subnet C"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["10.0.20.0/24"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "cgv-vpc-endpoint-sg" }
}

# --- S3 Gateway Endpoint (무료) ---
# ECR 이미지 레이어가 S3에 저장되므로, S3 Endpoint는 ECR Pull 성능에도 영향
resource "aws_vpc_endpoint" "s3" {
  vpc_id       = aws_vpc.main.id
  service_name = "com.amazonaws.ap-northeast-2.s3"

  vpc_endpoint_type = "Gateway"

  # App Private Route Table에 자동으로 라우팅 규칙 추가
  route_table_ids = [
    aws_route_table.app_private_a.id,
    aws_route_table.app_private_c.id,
  ]

  tags = { Name = "cgv-s3-gateway-endpoint" }
}

# --- ECR API Interface Endpoint ($0.01/hr) ---
# ecr.ap-northeast-2.amazonaws.com → VPC 내부 IP로 해석
# AZ-a만 배치: 비용 최적화 ($0.01/hr × 1 = $0.24/day)
# AZ-c 노드는 Cross-AZ로 접근 (ECR pull은 빈번하지 않으므로 비용 미미)
resource "aws_vpc_endpoint" "ecr_api" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.ap-northeast-2.ecr.api"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true # ECR 도메인을 VPC 내부 IP로 자동 해석

  subnet_ids         = [aws_subnet.app_private_a.id]
  security_group_ids = [aws_security_group.vpc_endpoint.id]

  tags = { Name = "cgv-ecr-api-endpoint" }
}

# --- ECR DKR Interface Endpoint ($0.01/hr) ---
# dkr.ecr.ap-northeast-2.amazonaws.com → Docker Pull/Push 트래픽
resource "aws_vpc_endpoint" "ecr_dkr" {
  vpc_id              = aws_vpc.main.id
  service_name        = "com.amazonaws.ap-northeast-2.ecr.dkr"
  vpc_endpoint_type   = "Interface"
  private_dns_enabled = true

  subnet_ids         = [aws_subnet.app_private_a.id]
  security_group_ids = [aws_security_group.vpc_endpoint.id]

  tags = { Name = "cgv-ecr-dkr-endpoint" }
}
