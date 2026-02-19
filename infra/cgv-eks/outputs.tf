# =============================================================================
# cgv-eks Outputs
# 2.2(Backend), 2.3(CI/CD), 2.4(Monitoring)에서 참조
# =============================================================================

output "cluster_endpoint" {
  description = "EKS API 서버 엔드포인트"
  value       = aws_eks_cluster.main.endpoint
}

output "cluster_name" {
  description = "EKS 클러스터 이름"
  value       = aws_eks_cluster.main.name
}

output "cluster_certificate_authority" {
  description = "EKS CA 인증서 (kubectl 설정에 필요)"
  value       = aws_eks_cluster.main.certificate_authority[0].data
}

output "oidc_issuer" {
  description = "EKS OIDC Provider URL"
  value       = aws_eks_cluster.main.identity[0].oidc[0].issuer
}

output "node_role_arn" {
  description = "Node IAM Role ARN (Karpenter EC2NodeClass에서 참조)"
  value       = aws_iam_role.eks_node.arn
}
