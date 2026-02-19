# =============================================================================
# cgv-database Outputs
# cgv-eks에서 terraform_remote_state로 참조 가능
# =============================================================================

# --- RDS ---
output "rds_endpoint" {
  description = "RDS MySQL endpoint (host:port)"
  value       = "${aws_db_instance.mysql.address}:${aws_db_instance.mysql.port}"
}

output "rds_address" {
  description = "RDS MySQL hostname"
  value       = aws_db_instance.mysql.address
}

# --- ElastiCache (Prod 전용) ---
output "redis_primary_endpoint" {
  description = "ElastiCache Redis Primary endpoint"
  value       = try(aws_elasticache_replication_group.redis[0].primary_endpoint_address, null)
}

output "redis_port" {
  description = "ElastiCache Redis port"
  value       = try(aws_elasticache_replication_group.redis[0].port, 6379)
}
