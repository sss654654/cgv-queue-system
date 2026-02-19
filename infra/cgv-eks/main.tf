# =============================================================================
# Stack 5: cgv-eks
# EKS Cluster + system MNG + EKS Add-ons (VPC CNI, CoreDNS, kube-proxy)
# =============================================================================

terraform {
  required_version = ">= 1.5.0"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 5.0"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
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

# --- Remote State 참조 ---
data "terraform_remote_state" "vpc" {
  backend = "s3"
  config = {
    bucket = "cgv-terraform-state"
    key    = "cgv-vpc/terraform.tfstate"
    region = "ap-northeast-2"
  }
}

data "aws_caller_identity" "current" {}

# =============================================================================
# 1. EKS Cluster
# =============================================================================
resource "aws_eks_cluster" "main" {
  name     = var.cluster_name
  version  = "1.31"              # EKS 버전 고정 (자동 업그레이드 방지)
  role_arn = aws_iam_role.eks_cluster.arn

  vpc_config {
    subnet_ids              = data.terraform_remote_state.vpc.outputs.app_private_subnet_ids
    endpoint_private_access = true
    endpoint_public_access  = true # 초기 배포. 안정화 후 public_access_cidrs 제한 또는 false 전환
  }

  # Control Plane 로그 → CloudWatch Logs (API 감사, 인증 이벤트 추적)
  enabled_cluster_log_types = ["api", "audit", "authenticator"]

  depends_on = [aws_iam_role_policy_attachment.eks_cluster_policy]

  tags = {
    Name        = var.cluster_name
    Environment = "shared"
  }
}

# =============================================================================
# 2. system Managed Node Group
# Karpenter, CoreDNS, kube-proxy가 실행되는 고정 노드
# =============================================================================
resource "aws_eks_node_group" "system" {
  cluster_name    = aws_eks_cluster.main.name
  node_group_name = "system"
  node_role_arn   = aws_iam_role.eks_node.arn
  subnet_ids      = data.terraform_remote_state.vpc.outputs.app_private_subnet_ids

  instance_types = ["t3.medium"] # 2 vCPU, 4GB RAM
  capacity_type  = "ON_DEMAND"   # system 노드는 반드시 On-Demand

  scaling_config {
    desired_size = 2             # CoreDNS HA를 위해 최소 2대 (→ 10.18.5)
    min_size     = 2             # AZ별 1대씩 분산 배치
    max_size     = 3             # 롤링 업데이트 시 여유
  }

  labels = {
    "node-role" = "system"       # kubectl get nodes에서 역할 확인용
  }

  # system 노드에는 taint 없음 — CoreDNS, kube-proxy가 스케줄링 가능해야 함

  depends_on = [
    aws_iam_role_policy_attachment.eks_worker_node_policy,
    aws_iam_role_policy_attachment.eks_cni_policy,
    aws_iam_role_policy_attachment.ecr_read_only,
  ]

  tags = {
    Name        = "cgv-system-node"
    Environment = "shared"
  }
}

# =============================================================================
# 3. EKS Add-ons
# =============================================================================
resource "aws_eks_addon" "vpc_cni" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "vpc-cni"
  resolve_conflicts_on_update = "OVERWRITE"
}

resource "aws_eks_addon" "coredns" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "coredns"
  resolve_conflicts_on_update = "OVERWRITE"

  # CoreDNS는 Deployment → 노드가 있어야 스케줄링됨
  depends_on = [aws_eks_node_group.system]
}

resource "aws_eks_addon" "kube_proxy" {
  cluster_name                = aws_eks_cluster.main.name
  addon_name                  = "kube-proxy"
  resolve_conflicts_on_update = "OVERWRITE"
}
