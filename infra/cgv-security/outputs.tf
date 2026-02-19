# =============================================================================
# cgv-security Outputs
# cgv-database, cgv-eks에서 terraform_remote_state로 참조
# =============================================================================

output "alb_sg_id" {
  description = "ALB Security Group ID"
  value       = aws_security_group.alb.id
}

output "node_sg_id" {
  description = "EKS Node Security Group ID"
  value       = aws_security_group.node.id
}

output "rds_sg_id" {
  description = "RDS Security Group ID"
  value       = aws_security_group.rds.id
}

output "elasticache_sg_id" {
  description = "ElastiCache Security Group ID"
  value       = aws_security_group.elasticache.id
}

output "waf_acl_arn" {
  description = "WAF Web ACL ARN (Ingress annotation에서 참조)"
  value       = aws_wafv2_web_acl.rate_limit.arn
}

# vpc_endpoint_sg_id → cgv-vpc/outputs.tf로 이동 (배포 순서 의존성)
