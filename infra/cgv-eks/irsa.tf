# =============================================================================
# IRSA (IAM Roles for Service Accounts) — 6개 역할
# 1. Monitoring (CloudWatch)
# 2. Karpenter (EC2 노드 프로비저닝)
# 3. ALB Controller (ALB 자동 생성)
# 4. KEDA (주석 처리 — Prometheus trigger 사용 중)
# 5. GitLab Runner (ECR Push + S3 Cache)
# 6. External Secrets Operator (ASM 읽기)
# =============================================================================

# =============================================================================
# 1. Monitoring IRSA Role
# =============================================================================
resource "aws_iam_role" "monitoring" {
  name = "cgv-monitoring-irsa-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_provider_url}:sub" = "system:serviceaccount:monitoring:monitoring-sa"
          "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "cgv-monitoring-irsa-role" }
}

resource "aws_iam_role_policy" "monitoring" {
  name = "cgv-monitoring-cloudwatch"
  role = aws_iam_role.monitoring.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "cloudwatch:PutMetricData",
        "cloudwatch:GetMetricData",
        "cloudwatch:ListMetrics"
      ]
      Resource = "*"
    }]
  })
}

# =============================================================================
# 2. Karpenter IRSA Role
# =============================================================================
resource "aws_iam_role" "karpenter" {
  name = "cgv-karpenter-irsa-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_provider_url}:sub" = "system:serviceaccount:kube-system:karpenter"
          "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "cgv-karpenter-irsa-role" }
}

resource "aws_iam_role_policy" "karpenter" {
  name = "cgv-karpenter-policy"
  role = aws_iam_role.karpenter.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ec2:CreateLaunchTemplate",
          "ec2:CreateFleet",
          "ec2:RunInstances",
          "ec2:TerminateInstances",
          "ec2:DescribeInstances",
          "ec2:DescribeInstanceTypes",
          "ec2:DescribeSubnets",
          "ec2:DescribeSecurityGroups",
          "ec2:DescribeLaunchTemplates",
          "ec2:DescribeImages",
          "ec2:DeleteLaunchTemplate",
          "ec2:CreateTags",
          "pricing:GetProducts"
        ]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = "iam:PassRole"
        Resource = aws_iam_role.eks_node.arn
      },
      {
        Effect = "Allow"
        Action = "ssm:GetParameter"
        Resource = "arn:aws:ssm:ap-northeast-2::parameter/aws/service/eks/optimized-ami/*"
      }
    ]
  })
}

# =============================================================================
# 3. ALB Controller IRSA Role
# =============================================================================
resource "aws_iam_role" "alb_controller" {
  name = "cgv-alb-controller-irsa-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_provider_url}:sub" = "system:serviceaccount:kube-system:aws-load-balancer-controller"
          "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "cgv-alb-controller-irsa-role" }
}

resource "aws_iam_role_policy" "alb_controller" {
  name   = "cgv-alb-controller-policy"
  role   = aws_iam_role.alb_controller.id
  policy = file("${path.module}/alb-controller-iam-policy.json")
}

# =============================================================================
# 4. KEDA IRSA Role (주석 처리)
# KEDA는 Prometheus 트리거를 사용하므로 CloudWatch IAM Role은 불필요하다.
# CloudWatch 트리거 전환 시 아래 패턴으로 IRSA Role을 추가한다 (→ 2.1 섹션 10.14 참고).
# =============================================================================
# resource "aws_iam_role" "keda" {
#   name = "cgv-keda-irsa-role"
#
#   assume_role_policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Effect = "Allow"
#       Principal = { Federated = local.oidc_provider_arn }
#       Action = "sts:AssumeRoleWithWebIdentity"
#       Condition = {
#         StringEquals = {
#           "${local.oidc_provider_url}:sub" = "system:serviceaccount:keda:keda-operator"
#           "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
#         }
#       }
#     }]
#   })
# }
#
# resource "aws_iam_role_policy" "keda" {
#   name = "cgv-keda-cloudwatch"
#   role = aws_iam_role.keda.id
#
#   policy = jsonencode({
#     Version = "2012-10-17"
#     Statement = [{
#       Effect = "Allow"
#       Action = [
#         "cloudwatch:GetMetricData",
#         "cloudwatch:ListMetrics",
#         "cloudwatch:DescribeAlarms"
#       ]
#       Resource = "*"
#     }]
#   })
# }

# =============================================================================
# 5. GitLab Runner IRSA Role (ECR Push + S3 Cache)
# =============================================================================
resource "aws_iam_role" "gitlab_runner" {
  name = "gitlab-runner-ecr-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_provider_url}:sub" = "system:serviceaccount:gitlab-runner:gitlab-runner"
          "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "gitlab-runner-ecr-role" }
}

resource "aws_iam_role_policy" "gitlab_runner" {
  name = "gitlab-runner-ecr-s3-policy"
  role = aws_iam_role.gitlab_runner.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = "ecr:GetAuthorizationToken"
        Resource = "*"
      },
      {
        Effect = "Allow"
        Action = [
          "ecr:BatchCheckLayerAvailability",
          "ecr:PutImage",
          "ecr:InitiateLayerUpload",
          "ecr:UploadLayerPart",
          "ecr:CompleteLayerUpload",
          "ecr:BatchGetImage",
          "ecr:GetDownloadUrlForLayer"
        ]
        Resource = "arn:aws:ecr:ap-northeast-2:${data.aws_caller_identity.current.account_id}:repository/cgv-queue-system"
      },
      {
        Effect = "Allow"
        Action = [
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket"
        ]
        Resource = [
          "arn:aws:s3:::cgv-ci-cache-${data.aws_caller_identity.current.account_id}",
          "arn:aws:s3:::cgv-ci-cache-${data.aws_caller_identity.current.account_id}/*"
        ]
      }
    ]
  })
}

# =============================================================================
# 6. External Secrets Operator IRSA Role (ASM 읽기)
# ESO가 AWS Secrets Manager에서 Secret을 읽어 K8s Secret으로 동기화한다.
# 2.3에서 ESO Helm 설치 시 이 Role ARN을 ServiceAccount annotation에 설정.
# SecretStore CRD의 serviceAccountRef.name = "cgv-api-sa" (→ 2.3 섹션 8.2)
# =============================================================================
resource "aws_iam_role" "external_secrets" {
  name = "cgv-external-secrets-irsa-role"

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Principal = { Federated = local.oidc_provider_arn }
      Action = "sts:AssumeRoleWithWebIdentity"
      Condition = {
        StringEquals = {
          "${local.oidc_provider_url}:sub" = "system:serviceaccount:cgv-prod:cgv-api-sa"
          "${local.oidc_provider_url}:aud" = "sts.amazonaws.com"
        }
      }
    }]
  })

  tags = { Name = "cgv-external-secrets-irsa-role" }
}

resource "aws_iam_role_policy" "external_secrets" {
  name = "cgv-external-secrets-asm-policy"
  role = aws_iam_role.external_secrets.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "secretsmanager:GetSecretValue",
          "secretsmanager:DescribeSecret"
        ]
        Resource = [
          "arn:aws:secretsmanager:ap-northeast-2:${data.aws_caller_identity.current.account_id}:secret:cgv/*"
        ]
      }
    ]
  })
}
