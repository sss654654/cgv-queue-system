# =============================================================================
# IAM Roles: EKS Cluster Role + Node Role
# =============================================================================

# --- Cluster IAM Role ---
# EKS 컨트롤 플레인이 AWS API를 호출하기 위한 역할
resource "aws_iam_role" "eks_cluster" {
  name = "cgv-eks-cluster-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "eks.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })

  tags = { Name = "cgv-eks-cluster-role" }
}

resource "aws_iam_role_policy_attachment" "eks_cluster_policy" {
  role       = aws_iam_role.eks_cluster.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSClusterPolicy"
}

# --- Node IAM Role ---
# Worker 노드(system MNG + Karpenter 노드)가 사용하는 역할
resource "aws_iam_role" "eks_node" {
  name = "cgv-eks-node-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Service = "ec2.amazonaws.com" }
      Action = "sts:AssumeRole"
    }]
  })

  tags = { Name = "cgv-eks-node-role" }
}

# EKS Worker 기본 정책
resource "aws_iam_role_policy_attachment" "eks_worker_node_policy" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKSWorkerNodePolicy"
}

# VPC CNI 플러그인이 ENI를 관리하기 위한 정책
resource "aws_iam_role_policy_attachment" "eks_cni_policy" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEKS_CNI_Policy"
}

# ECR에서 컨테이너 이미지 Pull 권한
resource "aws_iam_role_policy_attachment" "ecr_read_only" {
  role       = aws_iam_role.eks_node.name
  policy_arn = "arn:aws:iam::aws:policy/AmazonEC2ContainerRegistryReadOnly"
}
