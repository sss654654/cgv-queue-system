# =============================================================================
# Stack 2: cgv-vpc (네트워크)
# VPC, 3-Tier Subnet 6개, IGW, Dual NAT Gateway, Route Tables
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
  }
}

provider "aws" {
  region = "ap-northeast-2"

  default_tags {
    tags = {
      Project   = "cgv"
      ManagedBy = "terraform"
    }
  }
}

# =============================================================================
# VPC
# =============================================================================
resource "aws_vpc" "main" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_hostnames = true # VPC Endpoint Private DNS에 필수
  enable_dns_support   = true # VPC Endpoint Private DNS에 필수

  tags = {
    Name = "cgv-vpc"
  }
}

# =============================================================================
# Internet Gateway
# =============================================================================
resource "aws_internet_gateway" "main" {
  vpc_id = aws_vpc.main.id

  tags = {
    Name = "cgv-igw"
  }
}

# =============================================================================
# Public Subnets (ALB + NAT Gateway 전용)
# =============================================================================
resource "aws_subnet" "public_a" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.1.0/24"
  availability_zone       = "ap-northeast-2a"
  map_public_ip_on_launch = true

  tags = {
    Name                                = "cgv-public-subnet-a"
    "kubernetes.io/role/elb"            = "1" # ALB Controller: Public Subnet 자동 감지
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

resource "aws_subnet" "public_c" {
  vpc_id                  = aws_vpc.main.id
  cidr_block              = "10.0.2.0/24"
  availability_zone       = "ap-northeast-2c"
  map_public_ip_on_launch = true

  tags = {
    Name                                = "cgv-public-subnet-c"
    "kubernetes.io/role/elb"            = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

# =============================================================================
# App Private Subnets (EKS 노드 배치용)
# =============================================================================
resource "aws_subnet" "app_private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.10.0/24"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name                                = "cgv-app-private-subnet-a"
    "karpenter.sh/discovery"            = var.cluster_name # Karpenter가 이 서브넷에 노드 생성
    "kubernetes.io/role/internal-elb"    = "1"             # ALB Controller: Internal LB용
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

resource "aws_subnet" "app_private_c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.20.0/24"
  availability_zone = "ap-northeast-2c"

  tags = {
    Name                                = "cgv-app-private-subnet-c"
    "karpenter.sh/discovery"            = var.cluster_name
    "kubernetes.io/role/internal-elb"    = "1"
    "kubernetes.io/cluster/${var.cluster_name}" = "shared"
  }
}

# =============================================================================
# DB Private Subnets (RDS, ElastiCache 배치용)
# NAT Gateway 라우팅 불필요 — 외부 통신 경로 자체를 차단
# =============================================================================
resource "aws_subnet" "db_private_a" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.30.0/24"
  availability_zone = "ap-northeast-2a"

  tags = {
    Name = "cgv-db-private-subnet-a"
  }
}

resource "aws_subnet" "db_private_c" {
  vpc_id            = aws_vpc.main.id
  cidr_block        = "10.0.40.0/24"
  availability_zone = "ap-northeast-2c"

  tags = {
    Name = "cgv-db-private-subnet-c"
  }
}

# =============================================================================
# Elastic IP + NAT Gateway (Dual — AZ별 1개)
# ArgoCD → GitLab, GitLab Runner → gitlab.com, STS(IRSA) 용
# =============================================================================
resource "aws_eip" "nat_a" {
  domain = "vpc"
  tags   = { Name = "cgv-nat-eip-a" }
}

resource "aws_eip" "nat_c" {
  domain = "vpc"
  tags   = { Name = "cgv-nat-eip-c" }
}

resource "aws_nat_gateway" "a" {
  allocation_id = aws_eip.nat_a.id
  subnet_id     = aws_subnet.public_a.id

  tags = { Name = "cgv-nat-gateway-a" }

  depends_on = [aws_internet_gateway.main]
}

resource "aws_nat_gateway" "c" {
  allocation_id = aws_eip.nat_c.id
  subnet_id     = aws_subnet.public_c.id

  tags = { Name = "cgv-nat-gateway-c" }

  depends_on = [aws_internet_gateway.main]
}

# =============================================================================
# Route Tables
# =============================================================================

# --- Public Route Table ---
resource "aws_route_table" "public" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.main.id
  }

  tags = { Name = "cgv-public-rt" }
}

resource "aws_route_table_association" "public_a" {
  subnet_id      = aws_subnet.public_a.id
  route_table_id = aws_route_table.public.id
}

resource "aws_route_table_association" "public_c" {
  subnet_id      = aws_subnet.public_c.id
  route_table_id = aws_route_table.public.id
}

# --- App Private Route Table (AZ-a) → NAT Gateway-a ---
resource "aws_route_table" "app_private_a" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.a.id
  }

  tags = { Name = "cgv-app-private-rt-a" }
}

resource "aws_route_table_association" "app_private_a" {
  subnet_id      = aws_subnet.app_private_a.id
  route_table_id = aws_route_table.app_private_a.id
}

# --- App Private Route Table (AZ-c) → NAT Gateway-c ---
resource "aws_route_table" "app_private_c" {
  vpc_id = aws_vpc.main.id

  route {
    cidr_block     = "0.0.0.0/0"
    nat_gateway_id = aws_nat_gateway.c.id
  }

  tags = { Name = "cgv-app-private-rt-c" }
}

resource "aws_route_table_association" "app_private_c" {
  subnet_id      = aws_subnet.app_private_c.id
  route_table_id = aws_route_table.app_private_c.id
}

# --- DB Private Route Table (NAT 없음 — 외부 통신 차단) ---
resource "aws_route_table" "db_private" {
  vpc_id = aws_vpc.main.id

  # route 블록 없음 → 외부 통신 불가 (VPC 내부만)

  tags = { Name = "cgv-db-private-rt" }
}

resource "aws_route_table_association" "db_private_a" {
  subnet_id      = aws_subnet.db_private_a.id
  route_table_id = aws_route_table.db_private.id
}

resource "aws_route_table_association" "db_private_c" {
  subnet_id      = aws_subnet.db_private_c.id
  route_table_id = aws_route_table.db_private.id
}

# =============================================================================
# Data Sources
# =============================================================================
data "aws_caller_identity" "current" {}

# =============================================================================
# VPC Flow Logs (→ 2.1 섹션 10.18)
# =============================================================================
resource "aws_flow_log" "vpc" {
  vpc_id               = aws_vpc.main.id
  traffic_type         = "ALL"
  log_destination_type = "s3"
  log_destination      = "${aws_s3_bucket.flow_logs.arn}/"
  tags = { Name = "cgv-vpc-flow-log", Project = "cgv" }
}

resource "aws_s3_bucket" "flow_logs" {
  bucket = "cgv-vpc-flow-logs-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "cgv-vpc-flow-logs", Project = "cgv" }
}

resource "aws_s3_bucket_public_access_block" "flow_logs" {
  bucket                  = aws_s3_bucket.flow_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "flow_logs" {
  bucket = aws_s3_bucket.flow_logs.id
  rule {
    id     = "expire-old-logs"
    status = "Enabled"
    expiration { days = 30 }
  }
}

# =============================================================================
# CloudTrail (→ 2.1 섹션 10.18)
# =============================================================================
resource "aws_cloudtrail" "cgv" {
  name                          = "cgv-cloudtrail"
  s3_bucket_name                = aws_s3_bucket.cloudtrail.id
  include_global_service_events = true
  is_multi_region_trail         = false
  enable_logging                = true
  enable_log_file_validation    = true   # CloudTrail 로그 무결성 검증
  tags = { Name = "cgv-cloudtrail", Project = "cgv" }

  # Bucket Policy가 먼저 적용되어야 CloudTrail이 S3에 쓸 수 있음
  depends_on = [aws_s3_bucket_policy.cloudtrail]
}

resource "aws_s3_bucket" "cloudtrail" {
  bucket = "cgv-cloudtrail-${data.aws_caller_identity.current.account_id}"
  tags   = { Name = "cgv-cloudtrail", Project = "cgv" }
}

resource "aws_s3_bucket_public_access_block" "cloudtrail" {
  bucket                  = aws_s3_bucket.cloudtrail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_policy" "cloudtrail" {
  bucket = aws_s3_bucket.cloudtrail.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "AWSCloudTrailAclCheck"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:GetBucketAcl"
        Resource  = aws_s3_bucket.cloudtrail.arn
      },
      {
        Sid       = "AWSCloudTrailWrite"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.cloudtrail.arn}/AWSLogs/*"
        Condition = { StringEquals = { "s3:x-amz-acl" = "bucket-owner-full-control" } }
      }
    ]
  })
}

# =============================================================================
# Budget Alarm (→ 2.1 섹션 10.18)
# =============================================================================
resource "aws_budgets_budget" "cgv" {
  name         = "cgv-monthly-budget"
  budget_type  = "COST"
  limit_amount = "50"
  limit_unit   = "USD"
  time_unit    = "MONTHLY"

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 80
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }

  notification {
    comparison_operator        = "GREATER_THAN"
    threshold                  = 100
    threshold_type             = "PERCENTAGE"
    notification_type          = "ACTUAL"
    subscriber_email_addresses = [var.alert_email]
  }
}
