# =============================================================================
# cgv-vpc Outputs
# cgv-security, cgv-database, cgv-eks에서 terraform_remote_state로 참조
# =============================================================================

output "vpc_id" {
  description = "VPC ID"
  value       = aws_vpc.main.id
}

output "app_private_subnet_ids" {
  description = "App Private Subnet IDs (EKS 노드 배치용)"
  value       = [aws_subnet.app_private_a.id, aws_subnet.app_private_c.id]
}

output "db_private_subnet_ids" {
  description = "DB Private Subnet IDs (RDS, ElastiCache 배치용)"
  value       = [aws_subnet.db_private_a.id, aws_subnet.db_private_c.id]
}

output "public_subnet_ids" {
  description = "Public Subnet IDs (ALB 배치용)"
  value       = [aws_subnet.public_a.id, aws_subnet.public_c.id]
}

output "flow_log_bucket" {
  description = "VPC Flow Logs S3 bucket name"
  value       = aws_s3_bucket.flow_logs.bucket
}

output "cloudtrail_bucket" {
  description = "CloudTrail S3 bucket name"
  value       = aws_s3_bucket.cloudtrail.bucket
}

output "vpc_endpoint_sg_id" {
  description = "VPC Endpoint Security Group ID"
  value       = aws_security_group.vpc_endpoint.id
}
