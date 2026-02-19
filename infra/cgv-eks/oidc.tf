# =============================================================================
# OIDC Provider
# IRSA(IAM Roles for Service Accounts)를 위한 OpenID Connect Provider
# Pod → ServiceAccount → STS AssumeRoleWithWebIdentity → IAM Role
# =============================================================================

# EKS OIDC 발급자의 TLS 인증서 → thumbprint 추출
data "tls_certificate" "eks" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

resource "aws_iam_openid_connect_provider" "eks" {
  url = aws_eks_cluster.main.identity[0].oidc[0].issuer

  client_id_list  = ["sts.amazonaws.com"]
  thumbprint_list = [data.tls_certificate.eks.certificates[0].sha1_fingerprint]

  tags = { Name = "${var.cluster_name}-oidc-provider" }
}

# IRSA Trust Policy에서 사용할 OIDC URL (https:// 제거)
locals {
  oidc_provider_url = replace(aws_iam_openid_connect_provider.eks.url, "https://", "")
  oidc_provider_arn = aws_iam_openid_connect_provider.eks.arn
}
