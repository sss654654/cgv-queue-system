# =============================================================================
# Stack 3: cgv-security (SG Chain)
# Security Group 5개 — SG 참조 기반 체인
# ALB SG → Node SG → RDS SG / ElastiCache SG / VPC Endpoint SG
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

# --- cgv-vpc State 참조 ---
data "terraform_remote_state" "vpc" {
  backend = "s3"
  config = {
    bucket = "cgv-terraform-state"
    key    = "cgv-vpc/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

# =============================================================================
# 1. ALB Security Group
# =============================================================================
resource "aws_security_group" "alb" {
  name        = "cgv-alb-sg"
  description = "ALB - HTTP/HTTPS from internet"
  vpc_id      = data.terraform_remote_state.vpc.outputs.vpc_id

  ingress {
    description = "HTTP from internet"
    from_port   = 80
    to_port     = 80
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  ingress {
    description = "HTTPS from internet"
    from_port   = 443
    to_port     = 443
    protocol    = "tcp"
    cidr_blocks = ["0.0.0.0/0"]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name = "cgv-alb-sg"
    # karpenter.sh/discovery 태그를 ALB SG에 붙이면 안 됨
    # Node SG에만 적용해야 Karpenter가 올바른 SG를 노드에 연결
  }
}

# =============================================================================
# 2. Node Security Group
# =============================================================================
resource "aws_security_group" "node" {
  name        = "cgv-node-sg"
  description = "EKS worker nodes"
  vpc_id      = data.terraform_remote_state.vpc.outputs.vpc_id

  # ALB → Node: NodePort range
  ingress {
    description     = "NodePort range from ALB"
    from_port       = 30000
    to_port         = 32767
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # ALB → Node: Health check (Spring Boot 8080)
  ingress {
    description     = "Health check from ALB"
    from_port       = 8080
    to_port         = 8080
    protocol        = "tcp"
    security_groups = [aws_security_group.alb.id]
  }

  # Node ↔ Node: 자기 참조 (Pod-to-Pod, CoreDNS, kubelet)
  ingress {
    description = "Inter-node communication"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    self        = true
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = {
    Name                         = "cgv-node-sg"
    "karpenter.sh/discovery"     = "cgv-cluster" # Karpenter가 이 SG를 노드에 연결
  }
}

# =============================================================================
# 3. RDS Security Group
# =============================================================================
resource "aws_security_group" "rds" {
  name        = "cgv-rds-sg"
  description = "RDS MySQL - from Node SG only"
  vpc_id      = data.terraform_remote_state.vpc.outputs.vpc_id

  ingress {
    description     = "MySQL from EKS nodes"
    from_port       = 3306
    to_port         = 3306
    protocol        = "tcp"
    security_groups = [aws_security_group.node.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "cgv-rds-sg" }
}

# =============================================================================
# 4. ElastiCache Security Group (Prod 전용, dev는 Redis Pod)
# =============================================================================
resource "aws_security_group" "elasticache" {
  name        = "cgv-elasticache-sg"
  description = "ElastiCache Redis - from Node SG only"
  vpc_id      = data.terraform_remote_state.vpc.outputs.vpc_id

  ingress {
    description     = "Redis from EKS nodes"
    from_port       = 6379
    to_port         = 6379
    protocol        = "tcp"
    security_groups = [aws_security_group.node.id]
  }

  egress {
    description = "All outbound"
    from_port   = 0
    to_port     = 0
    protocol    = "-1"
    cidr_blocks = ["0.0.0.0/0"]
  }

  tags = { Name = "cgv-elasticache-sg" }
}

# =============================================================================
# 5. VPC Endpoint Security Group → cgv-vpc/endpoints.tf로 이동
# VPC Endpoint는 cgv-vpc(Stack 2)에서 생성되므로, SG도 같은 스택에 위치해야
# 배포 순서 의존성 문제가 없다. cgv-security(Stack 3)에서 정의하면
# cgv-vpc 배포 시 SG가 아직 존재하지 않아 Endpoint에 적용할 수 없다.
# =============================================================================

# =============================================================================
# 6. WAF Web ACL (Rate Limiting — 봇 차단)
# ALB Controller가 생성하는 ALB에 Ingress annotation으로 연결:
#   alb.ingress.kubernetes.io/wafv2-acl-arn: <waf_acl_arn output 값>
# =============================================================================
resource "aws_wafv2_web_acl" "rate_limit" {
  name        = "cgv-rate-limit"
  scope       = "REGIONAL" # ALB용은 REGIONAL (CloudFront는 CLOUDFRONT)
  description = "CGV API Rate Limiting - IP당 5분간 2000회 초과 시 차단"

  default_action {
    allow {} # 기본: 모든 요청 허용
  }

  rule {
    name     = "rate-limit-per-ip"
    priority = 1

    action {
      block {} # 임계치 초과 시 차단 (403 Forbidden)
    }

    statement {
      rate_based_statement {
        limit              = 2000 # 5분간 IP당 최대 2,000회
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      sampled_requests_enabled   = true
      cloudwatch_metrics_enabled = true
      metric_name                = "cgv-rate-limit-rule"
    }
  }

  visibility_config {
    sampled_requests_enabled   = true
    cloudwatch_metrics_enabled = true
    metric_name                = "cgv-waf-acl"
  }

  tags = { Name = "cgv-waf-rate-limit" }
}

# WAF 비용: Web ACL $5/month + Rule $1/month + $0.60/백만 요청
# 이 프로젝트 규모에서 ~$6/month
